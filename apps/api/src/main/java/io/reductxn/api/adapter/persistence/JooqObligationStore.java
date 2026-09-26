package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.ObligationStore;
import io.reductxn.api.port.out.ObligationToRecord;
import io.reductxn.fx.api.FxQuote;
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
import java.util.List;
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
    private static final Field<UUID> QUOTE_ID = DSL.field("quote_id", UUID.class);
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

        Optional<Record> row = Optional.ofNullable(dsl.select(ID, GROUP_ID, DEBTOR_ID, CREDITOR_ID,
                        AMOUNT_MINOR, CURRENCY_CODE, CURRENCY_EXPONENT, MEMO, QUOTE_ID, INCURRED_AT,
                        VOIDED_AT)
                .from(OBLIGATIONS)
                .where(GROUP_ID.eq(groupId))
                .and(IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOne());

        return row.map(this::toRecorded);
    }

    @Override
    public void record(ObligationToRecord obligation) {
        Objects.requireNonNull(obligation, "obligation");

        dsl.insertInto(OBLIGATIONS)
                .columns(ID, GROUP_ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE,
                        CURRENCY_EXPONENT, MEMO, QUOTE_ID, IDEMPOTENCY_KEY, INCURRED_AT)
                .values(obligation.id(), obligation.groupId(), obligation.debtorId(),
                        obligation.creditorId(), obligation.amount().amountMinor(),
                        obligation.amount().currency().code(),
                        (short) obligation.amount().currency().minorUnitExponent(),
                        obligation.memo(), obligation.quoteId(), obligation.idempotencyKey(),
                        obligation.incurredAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    /**
     * One row as the obligation it records, with its locked quote when it has one.
     *
     * <p>The quote is fetched by id rather than joined - {@link FxQuoteRows} explains why - and a
     * {@code quote_id} that resolves to nothing is a fault rather than an absence: the row says the commitment
     * was cross-currency, and without the quote nothing can say what the creditor was promised. Reported with
     * the obligation's id, because that is what somebody has to look up.
     */
    private RecordedObligation toRecorded(Record row) {
        Money amount = Money.of(row.get(AMOUNT_MINOR),
                Currency.of(row.get(CURRENCY_CODE), row.get(CURRENCY_EXPONENT)));

        UUID quoteId = row.get(QUOTE_ID);
        FxQuote quote = quoteId == null ? null : quoteById(row.get(ID), quoteId);

        return new RecordedObligation(
                row.get(ID),
                row.get(GROUP_ID),
                row.get(DEBTOR_ID),
                row.get(CREDITOR_ID),
                amount,
                // What the creditor is to be paid in, which is the quote's currency when there is a quote and
                // the debt currency when there is not. The row itself cannot disagree with the quote: it has no
                // settlement currency column to disagree with.
                quote == null ? amount.currency() : quote.convertedAmount().currency(),
                quote,
                row.get(MEMO),
                row.get(VOIDED_AT) == null ? ObligationStatus.OPEN : ObligationStatus.VOIDED,
                row.get(INCURRED_AT).toInstant());
    }

    private FxQuote quoteById(UUID obligationId, UUID quoteId) {
        FxQuote quote = FxQuoteRows.byId(dsl, List.of(quoteId)).get(quoteId);

        if (quote == null) {
            throw new IllegalStateException(
                    "obligation " + obligationId + " refers to quote " + quoteId + ", which is not in "
                            + "fx_quotes. The obligation is cross-currency and the quote is what says at which "
                            + "rate and in which currency the creditor was to be paid, so this obligation cannot "
                            + "be read as it stands. A quote is never deleted (ADR 0004), so this means the row "
                            + "was written by hand or the two were written in different transactions.");
        }

        return quote;
    }
}
