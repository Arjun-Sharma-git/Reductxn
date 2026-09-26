package io.reductxn.settlement.adapter.persistence;

import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.CycleObligation;
import io.reductxn.planning.api.SingleCurrencyObligation;
import io.reductxn.settlement.port.out.OpenObligations;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The group's open obligations, as the planner consumes them.
 *
 * <p>A read model over {@code obligations} ({@code apps/api}'s table) and {@code fx_quotes} ({@code libs/fx}'s),
 * which is what RULE B17 asks for between two modules that may not depend on each other (RULE B6). Inside
 * {@code adapter/persistence} because it uses jOOQ, and because nothing row-shaped may leave this package
 * (RULE B16).
 *
 * <p>This is a second reading of the same rows the API's preview reads, and that is deliberate rather than an
 * accident: a read model is a projection for one reader, and the two deployables may not share a class. What
 * they must share is what an obligation <em>is</em>, so the guard that matters is repeated here - a
 * cross-currency obligation whose quote is missing, or whose quote disagrees with the obligation's own amount,
 * throws rather than being read as a single-currency one in the currency the creditor is not being paid in. If
 * the two readings drift, they drift visibly: both throw on the same input instead of one of them planning a
 * settlement nobody promised.
 *
 * <p>{@code asOf} means "open at that instant", as in the preview:
 * {@code voided_at IS NULL OR voided_at > asOf}. A run and a preview of the same cycle have to be the same
 * answer, or a group approves one thing and executes another.
 */
@Component
public class JooqOpenObligations implements OpenObligations {

    // The obligations table is aliased, and its columns are qualified, because this query now carries a
    // correlated subquery over two more of this module's tables - and an unqualified `id` inside it would
    // resolve against the wrong table rather than fail loudly. JooqChainHeads holds the long version of that
    // argument; the short one is that jOOQ's `DSL.field(String, Class)` returns null on a table built by
    // `DSL.table(String)`, so a qualifier has to be written out.
    private static final Table<Record> OBLIGATIONS = DSL.table("obligations").as("obligation");
    private static final Table<Record> FX_QUOTES = DSL.table("fx_quotes");

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

    /** The run's link table and the runs themselves, for the "already settled" exclusion. */
    private static final Table<Record> SETTLED = DSL.table("settlement_run_obligations").as("settled");
    private static final Field<UUID> SETTLED_RUN = DSL.field(DSL.name("settled", "run_id"), UUID.class);
    private static final Field<UUID> SETTLED_OBLIGATION =
            DSL.field(DSL.name("settled", "obligation_id"), UUID.class);

    private static final Table<Record> RUNS = DSL.table("settlement_runs").as("settled_run");
    private static final Field<UUID> RUN_ID = DSL.field(DSL.name("settled_run", "id"), UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field(DSL.name("settled_run", "status"), String.class);

    private static final Field<UUID> Q_ID = DSL.field("id", UUID.class);
    private static final Field<String> Q_BASE_CODE = DSL.field("base_code", String.class);
    private static final Field<Short> Q_BASE_EXPONENT = DSL.field("base_exponent", Short.class);
    private static final Field<Long> Q_BASE_AMOUNT_MINOR = DSL.field("base_amount_minor", Long.class);
    private static final Field<String> Q_QUOTE_CODE = DSL.field("quote_code", String.class);
    private static final Field<Short> Q_QUOTE_EXPONENT = DSL.field("quote_exponent", Short.class);
    private static final Field<Long> Q_CONVERTED_MINOR = DSL.field("converted_amount_minor", Long.class);
    private static final Field<Long> Q_RESIDUAL_SCALED = DSL.field("residual_scaled", Long.class);
    private static final Field<Short> Q_RESIDUAL_SCALE = DSL.field("residual_scale", Short.class);
    private static final Field<Long> Q_RATE_SCALED = DSL.field("rate_scaled", Long.class);
    private static final Field<Short> Q_RATE_SCALE = DSL.field("rate_scale", Short.class);
    private static final Field<Long> Q_MID_RATE_SCALED = DSL.field("mid_rate_scaled", Long.class);
    private static final Field<Integer> Q_SPREAD_BPS = DSL.field("spread_bps", Integer.class);
    private static final Field<String> Q_RATE_SOURCE = DSL.field("rate_source", String.class);
    private static final Field<OffsetDateTime> Q_RATE_QUOTED_AT =
            DSL.field("rate_quoted_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> Q_RATE_EXPIRES_AT =
            DSL.field("rate_expires_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> Q_LOCKED_AT = DSL.field("locked_at", OffsetDateTime.class);
    private static final Field<Boolean> Q_RAIL_PERFORMED_FX = DSL.field("rail_performed_fx", Boolean.class);

    private final DSLContext dsl;

    public JooqOpenObligations(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<OpenCommitment> openFor(UUID groupId, Instant asOf) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        OffsetDateTime asOfUtc = asOf.atOffset(ZoneOffset.UTC);

        List<Record> rows = new ArrayList<>(dsl.select(
                        ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE, CURRENCY_EXPONENT, QUOTE_ID)
                .from(OBLIGATIONS)
                .where(GROUP_ID.eq(groupId))
                .and(INCURRED_AT.le(asOfUtc))
                .and(VOIDED_AT.isNull().or(VOIDED_AT.gt(asOfUtc)))
                // What a settled run has already discharged is not open any more. Without this, a later cycle
                // would net the same commitments again and the group would move money twice for one promise -
                // which is the whole reason `settlement_run_obligations` exists.
                //
                // "Settled" is the run's status rather than a column on the obligation: a run that is `failed`
                // moved some of the group's money and settled nothing (RULE S3, in the spec's own words), so the
                // status is the fact and a flag copied onto the obligation would be a second copy of it. The
                // link is a table rather than a derivation from instants because `incurred_at` is client-supplied
                // and may be backdated - V504 sets that argument out.
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

        // One query for every quote in the cycle, after the obligations have been read. A join would need a
        // second set of aliased column names for the same table, and a query per obligation would make a large
        // cycle N+1 - the same reasoning, and the same two queries, as the API's read model.
        Map<UUID, FxQuote> quotes = quotesById(quoteIds);

        List<OpenCommitment> cycle = new ArrayList<>(rows.size());
        for (Record row : rows) {
            // The identity travels with the commitment: a run records which obligations it settled, and an
            // obligation whose id was dropped on the way in cannot be referred to on the way out.
            cycle.add(new OpenCommitment(row.get(ID), toObligation(row, quotes)));
        }

        return List.copyOf(cycle);
    }

    /**
     * One row as the planner's type, with its locked quote when it has one.
     *
     * <p>The two guards throw rather than guess, and they are the same two the API's read model applies. A row
     * whose {@code quote_id} resolves to nothing says the commitment was cross-currency and cannot say what the
     * creditor was promised; a quote whose base disagrees with the obligation's own amount means one of them is
     * wrong about what the debtor owes. Either way there is no plan to make, and the alternative - preferring
     * one of the two readings - settles a cycle for an amount nobody agreed to.
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
                            + "fx_quotes, so the cycle cannot be planned: the quote is what says which currency "
                            + "the creditor is to be paid in. A quote is never deleted (ADR 0004), so this means "
                            + "the row was written by hand.");
        }

        if (!quote.baseAmount().equals(owed)) {
            throw new IllegalStateException(
                    "obligation " + row.get(ID) + " is for " + owed + " but its quote " + quoteId + " was "
                            + "locked for " + quote.baseAmount() + ". Both are the same fact - what the debtor "
                            + "owes - and they disagree, so settling this cycle would move an amount nobody "
                            + "promised.");
        }

        return new CrossCurrencyObligation(debtorId, creditorId, quote);
    }

    /**
     * The quotes with these ids, in one query.
     *
     * <p>Rebuilt through {@code FxRate} and {@code FxQuote}, both of which refuse to exist malformed, so a row
     * that broke an invariant on the way in fails on the way out rather than being planned from.
     */
    private Map<UUID, FxQuote> quotesById(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<UUID, FxQuote> quotes = new HashMap<>();
        for (Record row : dsl.select(Q_ID, Q_BASE_CODE, Q_BASE_EXPONENT, Q_BASE_AMOUNT_MINOR, Q_QUOTE_CODE,
                        Q_QUOTE_EXPONENT, Q_CONVERTED_MINOR, Q_RESIDUAL_SCALED, Q_RESIDUAL_SCALE, Q_RATE_SCALED,
                        Q_RATE_SCALE, Q_MID_RATE_SCALED, Q_SPREAD_BPS, Q_RATE_SOURCE, Q_RATE_QUOTED_AT,
                        Q_RATE_EXPIRES_AT, Q_LOCKED_AT, Q_RAIL_PERFORMED_FX)
                .from(FX_QUOTES)
                .where(Q_ID.in(ids))
                .fetch()) {

            Currency base = Currency.of(row.get(Q_BASE_CODE), row.get(Q_BASE_EXPONENT));
            Currency converted = Currency.of(row.get(Q_QUOTE_CODE), row.get(Q_QUOTE_EXPONENT));

            FxRate rate = new FxRate(base, converted, row.get(Q_RATE_SCALED), row.get(Q_RATE_SCALE),
                    row.get(Q_MID_RATE_SCALED), row.get(Q_SPREAD_BPS),
                    row.get(Q_RATE_QUOTED_AT).toInstant(), row.get(Q_RATE_EXPIRES_AT).toInstant(),
                    row.get(Q_RATE_SOURCE));

            FxQuote quote = new FxQuote(row.get(Q_ID), rate,
                    Money.of(row.get(Q_BASE_AMOUNT_MINOR), base),
                    Money.of(row.get(Q_CONVERTED_MINOR), converted),
                    row.get(Q_RESIDUAL_SCALED), row.get(Q_RESIDUAL_SCALE),
                    row.get(Q_LOCKED_AT).toInstant(), row.get(Q_RAIL_PERFORMED_FX));

            quotes.put(quote.id(), quote);
        }

        return quotes;
    }
}
