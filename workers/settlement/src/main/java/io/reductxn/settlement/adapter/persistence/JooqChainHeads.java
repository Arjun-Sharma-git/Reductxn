package io.reductxn.settlement.adapter.persistence;

import io.reductxn.booking.api.ChainHead;
import io.reductxn.ledger.api.Currency;
import io.reductxn.settlement.port.out.ChainHeads;

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
 * The chain head of a {@code (group, currency)}, read under a lock that outlives the read.
 *
 * <p><strong>This is the second implementation of the same query, and that is a decision with a cost.</strong>
 * {@code apps/api} has one, for the entries it appends when an obligation is recorded; this module needs one
 * for the entries it appends when a transfer confirms, and the two deployables may not share code (RULE B6).
 * Extracting it would need a new home - {@code libs/ledger} is pure and cannot hold a query,
 * {@code platform/persistence} owns no behaviour and would acquire knowledge of the ledger's tables - and
 * section 12 of the plan says that is an ADR rather than an afternoon's tidying.
 *
 * <p>So it is duplicated, and the risk is named rather than left to be discovered: <em>if the two copies
 * drift, one of them may lose the lock, and a missing lock does not fail - it forks a chain under load and
 * nothing notices until a verifier runs.</em> That is written here, in MASTER-PLAN's findings, and in
 * {@code apps/api}'s copy, because a duplicate that nobody knows is a duplicate is worse than one that is
 * accounted for. The trigger for extracting it is a third writer.
 *
 * <p>Everything the other copy does is done here, for the same reasons: the advisory lock is taken
 * <em>before</em> the read ({@code pg_advisory_xact_lock}, so it is held until commit); asking outside a
 * transaction throws, because a lock outside one protects nothing; and the chain's <em>leaf</em> is asked for
 * rather than its newest entry, because {@code occurred_at} says when a movement happened and nothing about
 * append order, so an entry written with a past instant would otherwise be mistaken for the head.
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

    private String headHash(UUID groupId, Currency currency) {
        Objects.requireNonNull(currency, "currency");

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "the chain for group " + groupId + " in " + currency.code() + " was asked for outside a "
                            + "transaction. The lock that makes this read safe is held until the transaction "
                            + "ends, so outside one it protects nothing and two appends can claim the same "
                            + "predecessor. ExecuteRunService commits each transfer's write in its own "
                            + "transaction for exactly this reason.");
        }

        // Group and currency are hashed by Postgres, so the key is stable for one chain and needs no
        // coordination across instances. Two chains colliding on a key would serialise against each other -
        // slower, and still correct.
        dsl.execute(DSL.sql("SELECT pg_advisory_xact_lock(hashtext({0}), hashtext({1}))",
                DSL.val(groupId.toString()), DSL.val(currency.code())));

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
