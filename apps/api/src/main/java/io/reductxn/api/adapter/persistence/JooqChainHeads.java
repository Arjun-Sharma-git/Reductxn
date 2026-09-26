package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.ChainHeads;
import io.reductxn.booking.api.ChainHead;
import io.reductxn.ledger.api.Currency;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The chain head of a {@code (group, currency)}, with the chain locked while the answer is used.
 *
 * <p><strong>The lock is the whole point of this class.</strong> {@code libs/booking} cannot build an entry
 * without its predecessor's hash, and two writers that read the same head both build an entry claiming the
 * same predecessor - an error invisible in either entry and visible only to a verifier. So the head is read
 * under a lock, and the lock has to outlive the read.
 *
 * <p>It is a Postgres advisory lock, taken for the length of the transaction
 * ({@code pg_advisory_xact_lock}), which is why {@link #headHash} refuses to run outside one. An in-process
 * lock was the obvious alternative and is not enough: a chain is shared by every instance of this
 * application, so a JVM lock serialises one instance and leaves the rest racing. A row lock on the chain's
 * last entry would be the other alternative and is worse - there is nothing to lock when the chain is empty,
 * which is exactly the case a group's first obligations all race for.
 *
 * <p>The lock key is a hash of the group and currency rather than the values themselves, because
 * {@code pg_advisory_xact_lock} takes integers. Two different chains hashing to the same key would serialise
 * against each other, which costs throughput and nothing else - the correctness comes from the key being
 * stable for one chain, not from it being unique.
 *
 * <p>{@code journal_entries_one_successor_per_hash} is the second line of defence: it makes a forked chain
 * unrepresentable, so if a writer ever gets past this lock the transaction fails instead of corrupting the
 * chain. That is also why this class asks for the chain's <em>leaf</em> rather than its newest entry -
 * {@code occurred_at} is when a commitment was made and says nothing about append order, so an obligation
 * recorded with a past instant would otherwise be mistaken for the head.
 */
@Component
public class JooqChainHeads implements ChainHeads {

    // Qualified fields rather than `table.field(name, type)`: on a table built by DSL.table(String) there is no
    // row type to derive a field from, and that call returns null - which fails later, at query time, as an
    // unhelpful NPE inside jOOQ's eq(). Naming the qualifier explicitly is what makes the correlation between
    // the two aliases mean something.
    private static final Table<Record> ENTRY = DSL.table("journal_entries").as("entry");
    private static final Table<Record> SUCCESSOR = DSL.table("journal_entries").as("successor");

    private static final Field<UUID> ENTRY_ID = DSL.field(DSL.name("entry", "id"), UUID.class);
    private static final Field<UUID> ENTRY_GROUP = DSL.field(DSL.name("entry", "group_id"), UUID.class);
    private static final Field<String> ENTRY_CURRENCY =
            DSL.field(DSL.name("entry", "currency_code"), String.class);
    private static final Field<String> ENTRY_HASH = DSL.field(DSL.name("entry", "hash"), String.class);
    private static final Field<OffsetDateTime> ENTRY_OCCURRED_AT =
            DSL.field(DSL.name("entry", "occurred_at"), OffsetDateTime.class);

    private static final Field<UUID> SUCCESSOR_GROUP =
            DSL.field(DSL.name("successor", "group_id"), UUID.class);
    private static final Field<String> SUCCESSOR_CURRENCY =
            DSL.field(DSL.name("successor", "currency_code"), String.class);
    private static final Field<String> SUCCESSOR_PREV_HASH =
            DSL.field(DSL.name("successor", "prev_hash"), String.class);

    private final DSLContext dsl;

    public JooqChainHeads(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public ChainHead forGroup(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return currency -> headHash(groupId, currency);
    }

    /**
     * The hash of the entry this chain currently ends at, or null when the chain is empty, read under a lock
     * that lasts until the transaction commits.
     *
     * <p>The lock is taken before the read, not after: taking it afterwards would serialise the writers and
     * still let both of them build an entry from the same stale head.
     */
    private String headHash(UUID groupId, Currency currency) {
        Objects.requireNonNull(currency, "currency");

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "the chain for group " + groupId + " in " + currency.code() + " was asked for outside a "
                            + "transaction. The lock that makes this read safe is held until the transaction "
                            + "ends, so outside one it protects nothing and two appends can claim the same "
                            + "predecessor. apps/api's use cases own that boundary (RULE B15), and "
                            + "RecordObligationService is annotated for exactly this reason.");
        }

        // Group and currency are hashed by Postgres, so the key is stable for one chain and needs no
        // coordination across instances. Two chains colliding on a key would serialise against each other -
        // slower, and still correct.
        dsl.execute(DSL.sql("SELECT pg_advisory_xact_lock(hashtext({0}), hashtext({1}))",
                DSL.val(groupId.toString()), DSL.val(currency.code())));

        // The chain's leaf rather than its newest entry by time: occurred_at is when the commitment was
        // made, so an obligation recorded with a past instant would be misread as the head and the next
        // append would fork the chain - which the unique index refuses, turning a silent corruption into a
        // failed request.
        return dsl.select(ENTRY_HASH)
                .from(ENTRY)
                .where(ENTRY_GROUP.eq(groupId))
                .and(ENTRY_CURRENCY.eq(currency.code()))
                .andNotExists(dsl.selectOne()
                        .from(SUCCESSOR)
                        .where(SUCCESSOR_GROUP.eq(groupId))
                        .and(SUCCESSOR_CURRENCY.eq(currency.code()))
                        .and(SUCCESSOR_PREV_HASH.eq(ENTRY_HASH)))
                .orderBy(ENTRY_OCCURRED_AT.desc(), ENTRY_ID.desc())
                .limit(1)
                .fetchOne(ENTRY_HASH);
    }
}
