package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.ObligationStore;
import io.reductxn.api.port.out.ObligationToRecord;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Obligations, in {@code obligations} - this module's own table (MASTER-PLAN section 5.5).
 *
 * <p>Written as a jOOQ repository without generated row types, like the two read models beside it: the
 * {@code Record} is mapped inside the fetch, so no jOOQ type leaves this package (RULE B16).
 *
 * <p><strong>An obligation's status is derived, not stored.</strong> {@code voided_at} is the only thing the
 * row knows, so null means open and non-null means voided. {@code netted} and {@code settled} are facts about
 * a settlement run, and runs live in {@code workers/settlement}'s tables, so this class cannot report either
 * one - the contract's enum has four values and this table can support two of them. Saying so here is the
 * point: a {@code status} column would be a second place the same fact lived, and it would be wrong the first
 * time a run was rolled back.
 */
@Component
public class JooqObligationStore implements ObligationStore {

    private static final Table<Record> OBLIGATIONS = DSL.table("obligations");
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<UUID> DEBTOR_ID = DSL.field("debtor_id", UUID.class);
    private static final Field<UUID> CREDITOR_ID = DSL.field("creditor_id", UUID.class);
    private static final Field<Long> AMOUNT_MINOR = DSL.field("amount_minor", Long.class);
    private static final Field<String> CURRENCY_CODE = DSL.field("currency_code", String.class);
    private static final Field<Short> CURRENCY_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<String> MEMO = DSL.field("memo", String.class);
    private static final Field<String> IDEMPOTENCY_KEY = DSL.field("idempotency_key", String.class);
    private static final Field<OffsetDateTime> INCURRED_AT =
            DSL.field("incurred_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> VOIDED_AT = DSL.field("voided_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqObligationStore(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<RecordedObligation> findExisting(UUID groupId, String idempotencyKey) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        return dsl.select(ID, GROUP_ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE,
                        CURRENCY_EXPONENT, MEMO, INCURRED_AT, VOIDED_AT)
                .from(OBLIGATIONS)
                .where(GROUP_ID.eq(groupId))
                .and(IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(JooqObligationStore::toRecorded);
    }

    @Override
    public void record(ObligationToRecord obligation) {
        Objects.requireNonNull(obligation, "obligation");

        dsl.insertInto(OBLIGATIONS)
                .columns(ID, GROUP_ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE,
                        CURRENCY_EXPONENT, MEMO, IDEMPOTENCY_KEY, INCURRED_AT)
                .values(obligation.id(), obligation.groupId(), obligation.debtorId(),
                        obligation.creditorId(), obligation.amount().amountMinor(),
                        obligation.amount().currency().code(),
                        (short) obligation.amount().currency().minorUnitExponent(),
                        obligation.memo(), obligation.idempotencyKey(),
                        obligation.incurredAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    /** One row as the obligation it records. */
    private static RecordedObligation toRecorded(Record row) {
        Money amount = Money.of(row.get(AMOUNT_MINOR),
                Currency.of(row.get(CURRENCY_CODE), row.get(CURRENCY_EXPONENT)));

        return new RecordedObligation(
                row.get(ID),
                row.get(GROUP_ID),
                row.get(DEBTOR_ID),
                row.get(CREDITOR_ID),
                amount,
                // The same currency as the debt, because the row cannot say otherwise: there is no
                // settlement-currency column and no quote to read. When there is one, this is the line that
                // changes - and the line that must, because an obligation stored without it would be read
                // back in the currency the creditor is not being paid in.
                amount.currency(),
                row.get(MEMO),
                row.get(VOIDED_AT) == null ? ObligationStatus.OPEN : ObligationStatus.VOIDED,
                row.get(INCURRED_AT).toInstant());
    }
}
