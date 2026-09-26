package io.reductxn.settlement.adapter.persistence;

import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Posting;
import io.reductxn.settlement.port.out.JournalEntries;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Appending a settlement entry and its postings to the ledger.
 *
 * <p>Two tables, one method, and the caller's transaction is the only boundary - the same arrangement
 * {@code apps/api}'s adapter has, for the same reason: the entry was built against a chain head that was read
 * under a lock which lasts until commit, so an append in a different transaction would be outside it.
 *
 * <p><strong>Posting ids are derived, not generated.</strong> {@code Posting} has no identity beyond its
 * entry, so the row's key is derived from the entry id and the posting's position - which means the two
 * postings of one entry cannot collide with each other, and a repeated append produces the same rows rather
 * than a second set.
 *
 * <p><strong>A duplicate is skipped rather than raised, and that is the difference from the API's adapter.</strong>
 * An entry's id here is derived from the transfer it records, and two worker instances may confirm the same
 * transfer at once: both compute the same id, the advisory lock serialises them, and the second append has
 * nothing to do. Raising would fail a request for a run that had in fact completed, which is a worse answer
 * than the no-op. What protects the chain is the lock and
 * {@code journal_entries_one_successor_per_hash}; what this line protects is a caller's error message.
 *
 * <p>{@code kind}, {@code direction} and {@code fx_role} are mapped with exhaustive switches rather than
 * {@code name().toLowerCase()}: the strings are compared against CHECK constraints, so a value a switch cannot
 * produce would fail at insert rather than at compile time.
 */
@Component
public class JooqJournalEntries implements JournalEntries {

    private static final Table<Record> JOURNAL_ENTRIES = DSL.table("journal_entries");
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<String> CURRENCY_CODE = DSL.field("currency_code", String.class);
    private static final Field<Short> CURRENCY_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<OffsetDateTime> OCCURRED_AT =
            DSL.field("occurred_at", OffsetDateTime.class);
    private static final Field<String> KIND = DSL.field("kind", String.class);
    private static final Field<UUID> CREATED_BY = DSL.field("created_by", UUID.class);
    private static final Field<String> IDEMPOTENCY_KEY = DSL.field("idempotency_key", String.class);
    private static final Field<UUID> FX_LINK_ID = DSL.field("fx_link_id", UUID.class);
    private static final Field<String> FX_ROLE = DSL.field("fx_role", String.class);
    private static final Field<Boolean> RAIL_PERFORMED_FX = DSL.field("rail_performed_fx", Boolean.class);
    private static final Field<String> PREV_HASH = DSL.field("prev_hash", String.class);
    private static final Field<String> HASH = DSL.field("hash", String.class);

    private static final Table<Record> POSTINGS = DSL.table("postings");
    private static final Field<UUID> POSTING_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> ENTRY_ID = DSL.field("entry_id", UUID.class);
    private static final Field<Short> POSITION = DSL.field("position", Short.class);
    private static final Field<UUID> ACCOUNT_ID = DSL.field("account_id", UUID.class);
    private static final Field<String> DIRECTION = DSL.field("direction", String.class);
    private static final Field<Long> AMOUNT_MINOR = DSL.field("amount_minor", Long.class);

    private final DSLContext dsl;

    public JooqJournalEntries(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void append(JournalEntry entry) {
        Objects.requireNonNull(entry, "entry");

        dsl.insertInto(JOURNAL_ENTRIES)
                .columns(ID, GROUP_ID, CURRENCY_CODE, CURRENCY_EXPONENT, OCCURRED_AT, KIND, CREATED_BY,
                        IDEMPOTENCY_KEY, FX_LINK_ID, FX_ROLE, RAIL_PERFORMED_FX, PREV_HASH, HASH)
                .values(entry.id(), entry.groupId(), entry.currency().code(),
                        (short) entry.currency().minorUnitExponent(),
                        entry.occurredAt().atOffset(ZoneOffset.UTC),
                        kindName(entry.kind()), entry.createdBy(), entry.idempotencyKey(), entry.fxLinkId(),
                        fxRoleName(entry.fxRole()), entry.railPerformedFx(),
                        // The derived hash, stored because it is what the offline verifier compares against the
                        // rows rather than against a value the application recomputed for it.
                        entry.prevHash(), entry.hash())
                .onConflict(ID)
                .doNothing()
                .execute();

        List<Posting> postings = entry.postings();
        for (int position = 0; position < postings.size(); position++) {
            Posting posting = postings.get(position);

            dsl.insertInto(POSTINGS)
                    .columns(POSTING_ID, ENTRY_ID, POSITION, ACCOUNT_ID, DIRECTION, AMOUNT_MINOR,
                            CURRENCY_CODE, CURRENCY_EXPONENT)
                    .values(postingId(entry.id(), position), entry.id(), (short) position, posting.accountId(),
                            directionName(posting.direction()), posting.amount().amountMinor(),
                            posting.amount().currency().code(),
                            (short) posting.amount().currency().minorUnitExponent())
                    .onConflict(POSTING_ID)
                    .doNothing()
                    .execute();
        }
    }

    /** A posting's row identity, derived so a repeated append writes nothing new. */
    private static UUID postingId(UUID entryId, int position) {
        return UUID.nameUUIDFromBytes((entryId + ":posting:" + position).getBytes(StandardCharsets.UTF_8));
    }

    /** {@link EntryKind} as the {@code kind} column's CHECK constraint spells it. */
    private static String kindName(EntryKind kind) {
        return switch (kind) {
            case OBLIGATION -> "obligation";
            case SETTLEMENT -> "settlement";
            case FEE -> "fee";
            case TAX -> "tax";
            case FX -> "fx";
            case ADJUSTMENT -> "adjustment";
        };
    }

    /** {@link FxRole} as the {@code fx_role} column spells it, or null when the entry is not linked. */
    private static String fxRoleName(FxRole role) {
        return role == null ? null : switch (role) {
            case BASE -> "base";
            case QUOTE -> "quote";
        };
    }

    /** {@link Direction} as one character: the sign is the direction, never the amount (L2). */
    private static String directionName(Direction direction) {
        return switch (direction) {
            case DEBIT -> "D";
            case CREDIT -> "C";
        };
    }
}
