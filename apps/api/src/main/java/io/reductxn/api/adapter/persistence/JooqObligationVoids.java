package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.ObligationVoids;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Voiding an obligation: one read, one conditional update, one read back.
 *
 * <p><strong>An aliased table, because the status derivation reaches into {@code settlement_runs}.</strong> An unqualified
 * {@code id} inside that correlated subquery resolves against the subquery's own scope - a runtime error rather than a
 * compile error. {@code JooqObligationStore} records the same trap, and the alias is local to each statement because jOOQ
 * will not take an aliased table as an update target.
 *
 * <p><strong>The mapping is the store's shape, written out again rather than shared.</strong> Column-to-value conversion,
 * including the quote fetched by id, now exists in two classes - once for the idempotency-keyed read and once here. A shared
 * helper would be a third class with two callers, and the two answer different questions: "the obligation this key already
 * made" versus "the obligation with this id". What is *not* duplicated is the decision - the status comes from
 * {@code ObligationStatuses} in both, so a voided obligation cannot be described one way to a replayer and another to a
 * voider (ADR 0036).
 */
@Repository
public class JooqObligationVoids implements ObligationVoids {

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
    private static final Field<OffsetDateTime> INCURRED_AT = DSL.field("incurred_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> VOIDED_AT = DSL.field("voided_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqObligationVoids(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<RecordedObligation> findOne(UUID obligationId) {
        Objects.requireNonNull(obligationId, "obligationId");

        return Optional.ofNullable(readBy(obligationId)).map(this::toRecorded);
    }

    @Override
    public RecordedObligation setVoidedAt(UUID obligationId, Instant at) {
        Objects.requireNonNull(obligationId, "obligationId");
        Objects.requireNonNull(at, "at");

        // The state is in the WHERE clause, not in a statement the service is trusted to have checked: two callers can both
        // decide the obligation is open, and only one of them may write. `IS NULL` makes the loser's update a no-op rather
        // than a second claim about when it was voided.
        dsl.update(OBLIGATIONS)
                .set(VOIDED_AT, at.atOffset(ZoneOffset.UTC))
                .where(ID.eq(obligationId))
                .and(VOIDED_AT.isNull())
                .execute();

        Record row = readBy(obligationId);

        if (row == null) {
            throw new IllegalStateException("obligation " + obligationId + " was voided and is now unreadable. "
                    + "Obligations are never deleted (ADR 0004), so this means the row was removed by hand.");
        }

        return toRecorded(row);
    }

    private Record readBy(UUID obligationId) {
        Table<Record> obligations = DSL.table("obligations").as("obligation");
        Field<UUID> id = DSL.field(DSL.name("obligation", "id"), UUID.class);
        Field<OffsetDateTime> voidedAt = DSL.field(DSL.name("obligation", "voided_at"), OffsetDateTime.class);

        return dsl.select(ID, GROUP_ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE, CURRENCY_EXPONENT,
                        MEMO, QUOTE_ID, INCURRED_AT, VOIDED_AT, ObligationStatuses.of(id, voidedAt).as("status"))
                .from(obligations)
                .where(ID.eq(obligationId))
                .fetchOne();
    }

    private RecordedObligation toRecorded(Record row) {
        Money amount = Money.of(row.get(AMOUNT_MINOR),
                Currency.of(row.get(CURRENCY_CODE), row.get(CURRENCY_EXPONENT)));

        UUID quoteId = row.get(QUOTE_ID);
        FxQuote quote = quoteId == null ? null : FxQuoteRows.byId(dsl, List.of(quoteId)).get(quoteId);

        if (quoteId != null && quote == null) {
            throw new IllegalStateException("obligation " + row.get(ID) + " refers to quote " + quoteId
                    + ", which is not in fx_quotes. A quote is never deleted (ADR 0004), so this means the row was "
                    + "written by hand or the two were written in different transactions.");
        }

        return new RecordedObligation(
                row.get(ID),
                row.get(GROUP_ID),
                row.get(DEBTOR_ID),
                row.get(CREDITOR_ID),
                amount,
                quote == null ? amount.currency() : quote.convertedAmount().currency(),
                quote,
                row.get(MEMO),
                ObligationStatus.fromWire(row.get("status", String.class)).orElseThrow(() -> new IllegalStateException(
                        "obligation " + row.get(ID) + " has status '" + row.get("status", String.class)
                                + "', which is not one of " + ObligationStatus.vocabulary())),
                row.get(INCURRED_AT).toInstant());
    }
}
