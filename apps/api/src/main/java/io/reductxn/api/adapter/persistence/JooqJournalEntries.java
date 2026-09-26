package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.JournalEntries;
import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Posting;

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
 * Appending an entry and its postings to {@code journal_entries} and {@code postings}.
 *
 * <p><strong>Posting ids are derived, not generated.</strong> {@code Posting} has no id - it is an account, a
 * direction and a magnitude, with no identity beyond its entry - so the row's key is derived from the entry
 * id and the posting's position. A generated key would work, and it would make a re-appended entry produce
 * different rows than the first attempt, which is the property {@code libs/booking} went to some trouble to
 * establish for the entries themselves. Deriving it means a retried append collides rather than doubling.
 *
 * <p>{@code kind}, {@code direction} and {@code fx_role} are mapped with exhaustive switches rather than
 * {@code name().toLowerCase()}. The mapper in {@code web/dto} gives the reason for that convention -
 * lower-casing with the default locale is both a localization bug and a rename hazard - and it applies more
 * sharply here: these strings are compared against CHECK constraints, so a value a switch cannot produce
 * would fail at insert rather than at compile time.
 *
 * <p>Both tables are written in one method, so the caller's transaction is the only boundary. There is no
 * batch: an entry has two postings in the ordinary case, and a batch behind PgBouncer is the thing
 * {@code platform/persistence} rewrites client-side rather than relies on.
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
    private static final Field<UUID> ACCOUNT_ID = DSL.field("account_id", UUID.class);
    private static final Field<Short> POSITION = DSL.field("position", Short.class);
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
                        kindName(entry.kind()), entry.createdBy(), entry.idempotencyKey(),
                        entry.fxLinkId(), fxRoleName(entry.fxRole()), entry.railPerformedFx(),
                        // The derived hash, stored because it is what the offline verifier compares against
                        // the rows rather than against a value the application recomputed for it.
                        entry.prevHash(), entry.hash())
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
                    .execute();
        }
    }

    /**
     * A posting's row identity, derived so a repeated append collides instead of doubling.
     *
     * <p>The position is part of the derivation rather than a random value, so the two postings of one entry
     * cannot collide with each other and the same entry always produces the same rows in the same places.
     */
    private static UUID postingId(UUID entryId, int position) {
        return UUID.nameUUIDFromBytes(
                (entryId + ":posting:" + position).getBytes(StandardCharsets.UTF_8));
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
