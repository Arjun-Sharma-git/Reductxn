package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.CycleObligation;
import io.reductxn.planning.api.SingleCurrencyObligation;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The open obligations of a group, read from {@code obligations}.
 *
 * <p>A jOOQ repository without generated row types, which is a deliberate choice rather than a shortcut.
 * jOOQ's code generator would give typed columns, and it would also put a generated class per table into
 * every signature that touched one - which is exactly what RULE B16 forbids from crossing out of this
 * package. Mapping a {@link Record} to {@link CycleObligation} inside the fetch keeps the row type local:
 * what leaves is the planner's own sealed type, so a change to a column cannot reach a use case.
 *
 * <p><strong>{@code asOf} means "open at that instant", not "created before it".</strong> An obligation
 * voided yesterday was open the day before, so the filter is
 * {@code voided_at IS NULL OR voided_at > asOf} rather than a null check alone. The difference matters
 * exactly when someone previews a past cycle, which is the only reason the parameter exists.
 *
 * <p><strong>A cross-currency obligation comes back with its locked quote.</strong> The row carries a
 * {@code quote_id} and the quote - {@code libs/fx}'s table, read through this package's one mapping of it -
 * carries the rate, the converted amount and the residual, which together are what the planner needs and what
 * an auditor would ask for. The obligation's own amount and the quote's base amount are the same fact stored
 * twice, and they are <em>compared</em> rather than trusted: removing either would leave one reader unable to
 * answer, so the check belongs where both are in hand.
 *
 * <p>Ordering is by {@code (incurred_at, id)}: the planner normalises order itself, but a stable order
 * makes a log or a failing test reproducible, and an unstable one makes both worthless.
 */
@Component
public class JooqOpenObligations implements OpenObligations {

    // Aliased, with qualified columns, because the query below carries a correlated subquery over the
    // settlement worker's tables (RULE B17's arrangement - this is a read model over another module's rows, not
    // a dependency on it): an unqualified `id` inside that subquery would resolve against the wrong table.
    private static final Table<Record> OBLIGATIONS = DSL.table("obligations").as("obligation");
    private static final Field<UUID> ID = DSL.field(DSL.name("obligation", "id"), UUID.class);
    private static final Field<UUID> GROUP_ID = DSL.field(DSL.name("obligation", "group_id"), UUID.class);
    private static final Field<UUID> DEBTOR_ID = DSL.field(DSL.name("obligation", "debtor_id"), UUID.class);
    private static final Field<UUID> CREDITOR_ID = DSL.field(DSL.name("obligation", "creditor_id"), UUID.class);
    private static final Field<Long> AMOUNT_MINOR = DSL.field(DSL.name("obligation", "amount_minor"), Long.class);
    private static final Field<String> CURRENCY_CODE =
            DSL.field(DSL.name("obligation", "currency_code"), String.class);
    private static final Field<Short> CURRENCY_EXPONENT =
            DSL.field(DSL.name("obligation", "currency_exponent"), Short.class);
    private static final Field<UUID> QUOTE_ID = DSL.field(DSL.name("obligation", "quote_id"), UUID.class);
    private static final Field<OffsetDateTime> INCURRED_AT =
            DSL.field(DSL.name("obligation", "incurred_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> VOIDED_AT =
            DSL.field(DSL.name("obligation", "voided_at"), OffsetDateTime.class);

    /** The settlement worker's link table and runs, for the "already settled" exclusion. */
    private static final Table<Record> SETTLED = DSL.table("settlement_run_obligations").as("settled");
    private static final Field<UUID> SETTLED_RUN = DSL.field(DSL.name("settled", "run_id"), UUID.class);
    private static final Field<UUID> SETTLED_OBLIGATION =
            DSL.field(DSL.name("settled", "obligation_id"), UUID.class);

    private static final Table<Record> RUNS = DSL.table("settlement_runs").as("settled_run");
    private static final Field<UUID> RUN_ID = DSL.field(DSL.name("settled_run", "id"), UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field(DSL.name("settled_run", "status"), String.class);

    private final DSLContext dsl;

    public JooqOpenObligations(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<CycleObligation> openFor(UUID groupId, Instant asOf) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        // An instant is absolute, so the comparison is in UTC and no timezone enters it. Evaluating a tax
        // rule date in the group's settlement timezone is a different question and happens later (ADR 0014
        // point 6); conflating the two is how a run stops being reproducible.
        OffsetDateTime asOfUtc = asOf.atOffset(ZoneOffset.UTC);

        List<Record> rows = new ArrayList<>(dsl.select(ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE,
                        CURRENCY_EXPONENT, QUOTE_ID)
                .from(OBLIGATIONS)
                .where(GROUP_ID.eq(groupId))
                .and(INCURRED_AT.le(asOfUtc))
                .and(VOIDED_AT.isNull().or(VOIDED_AT.gt(asOfUtc)))
                // A commitment a settled run has already discharged is not open: the money for it has moved,
                // and a preview that still counted it would offer the group a cycle that would move it again.
                // The rule is the settlement worker's (`docs/specs/settlement.md`), read here because this
                // module's preview has to give the same answer the worker's own compute does - otherwise a
                // group previews one thing and the run that follows settles another.
                .andNotExists(dsl.selectOne()
                        .from(SETTLED)
                        .join(RUNS).on(RUN_ID.eq(SETTLED_RUN))
                        .where(SETTLED_OBLIGATION.eq(ID))
                        .and(RUN_STATUS.eq("done")))
                .orderBy(INCURRED_AT.asc(), ID.asc())
                .fetch());

        Set<UUID> quoteIds = new HashSet<>();
        for (Record row : rows) {
            UUID quoteId = row.get(QUOTE_ID);
            if (quoteId != null) {
                quoteIds.add(quoteId);
            }
        }

        // One query for every quote in the cycle, after the obligations have been read: a join would need a
        // second set of aliased column names for the same table, and a query per obligation would make a
        // preview of a large group N+1. FxQuoteRows.byId holds the reasoning.
        Map<UUID, FxQuote> quotes = FxQuoteRows.byId(dsl, quoteIds);

        List<CycleObligation> obligations = new ArrayList<>(rows.size());
        for (Record row : rows) {
            obligations.add(toObligation(row, quotes));
        }

        return List.copyOf(obligations);
    }

    /**
     * One row as the planner's type.
     *
     * <p>The exponent travels with the amount rather than being looked up, so a historical obligation means
     * the same thing it meant when it was recorded.
     *
     * <p>A quote that is missing, or whose base disagrees with the obligation's own amount, throws. Both are
     * faults rather than preferences: the row says the commitment was cross-currency, and without an agreeing
     * quote there is no answer to what the creditor was promised. Throwing is deliberate - the alternative is
     * planning a settlement in a currency the creditor is not being paid in, which is the specific error the
     * storage of a quote exists to prevent.
     */
    private static CycleObligation toObligation(Record row, Map<UUID, FxQuote> quotes) {
        UUID debtorId = row.get(DEBTOR_ID);
        UUID creditorId = row.get(CREDITOR_ID);
        Currency currency = Currency.of(row.get(CURRENCY_CODE), row.get(CURRENCY_EXPONENT));
        Money owed = Money.of(row.get(AMOUNT_MINOR), currency);

        UUID quoteId = row.get(QUOTE_ID);
        if (quoteId == null) {
            return new SingleCurrencyObligation(debtorId, creditorId, owed);
        }

        FxQuote quote = quotes.get(quoteId);
        if (quote == null) {
            throw new IllegalStateException(
                    "obligation " + row.get(ID) + " refers to quote " + quoteId + ", which is not in "
                            + "fx_quotes, so it cannot be planned: the quote is what says which currency the "
                            + "creditor is to be paid in. A quote is never deleted (ADR 0004), so this means "
                            + "the row was written by hand.");
        }

        if (!quote.baseAmount().equals(owed)) {
            throw new IllegalStateException(
                    "obligation " + row.get(ID) + " is for " + owed + " but its quote " + quoteId + " was "
                            + "locked for " + quote.baseAmount() + ". Both are the same fact - what the debtor "
                            + "owes - stored once where the plan reads it and once where the conversion used "
                            + "it, and they disagree. Preferring either would record a commitment nobody "
                            + "made.");
        }

        return new CrossCurrencyObligation(debtorId, creditorId, quote);
    }
}
