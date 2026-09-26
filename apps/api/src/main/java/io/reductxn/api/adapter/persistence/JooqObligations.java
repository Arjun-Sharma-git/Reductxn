package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.ObligationFilter;
import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.Obligations;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
 * A group's obligations, as a page, read from {@code obligations} and the worker's runs.
 *
 * <p><strong>The status is derived in a subquery, and that is the interesting decision here.</strong> SQL cannot
 * filter on a select alias, so the two obvious shapes were a {@code WHERE} that repeats the four-branch CASE or a
 * derived table that computes it once and filters the result. The second is what this does, and the reason is not
 * brevity: with the first, the column the screen receives and the condition that decides whether the row is in the
 * page are two expressions that can drift, and a row could be shown as {@code open} under a filter for {@code open}
 * that disagrees. With the subquery they are one expression, so a filter cannot select a row whose status it would
 * not display ({@code ObligationStatuses} holds it).
 *
 * <p><strong>Every field is qualified by the alias.</strong> The derivation's subqueries reach two other tables, and
 * an unqualified {@code id} resolves against whichever table jOOQ puts in scope - the runtime failure
 * {@code JooqCycles} records rather than a compile error. The inner select aliases the obligations table and the
 * outer one reads only from the derived table, so there is one scope at each level and one name per column.
 *
 * <p>A quote is fetched for the whole page in one query rather than per row ({@link FxQuoteRows} holds the argument),
 * and an invoice whose {@code quote_id} resolves to nothing is a fault: the row says the commitment was
 * cross-currency and the quote is what says what the creditor was promised.
 */
@Component
public class JooqObligations implements Obligations {

    // Every column is qualified by the alias, because the status derivation below reaches two of the worker's
    // tables in correlated subqueries: an unqualified `id` inside one of those resolves against that subquery's own
    // scope - `settlement_runs` has an `id` too, so the comparison would silently be between the wrong two things,
    // which is worse than an error. `JooqCycles` and `JooqOpenObligations` record the same trap.
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
    private static final Field<String> MEMO = DSL.field(DSL.name("obligation", "memo"), String.class);
    private static final Field<UUID> QUOTE_ID = DSL.field(DSL.name("obligation", "quote_id"), UUID.class);
    private static final Field<OffsetDateTime> INCURRED_AT =
            DSL.field(DSL.name("obligation", "incurred_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> VOIDED_AT =
            DSL.field(DSL.name("obligation", "voided_at"), OffsetDateTime.class);

    /**
     * The status: one Java expression, rendered twice.
     *
     * <p>`STATUS_EXPRESSION` is what the filter compares, and {@link #STATUS} is the same expression named so the
     * response carries it. Both come from one call to the derivation, so there is nothing to keep in step - and the
     * two renderings are not interchangeable, which is what a first attempt at this got wrong: PostgreSQL does not
     * resolve a select alias in a `WHERE`, so `status = ?` is a syntax error, and a derived table that would allow it
     * names every column (`"page"."incurred_at"`) and hides the point.
     */
    private static final Field<String> STATUS_EXPRESSION = ObligationStatuses.of(ID, VOIDED_AT);

    private static final Field<String> STATUS = STATUS_EXPRESSION.as("status");

    /** The response's status, read back from the result the query produced. */
    private static final Field<String> RESULT_STATUS = DSL.field(DSL.name("status"), String.class);

    private final DSLContext dsl;

    public JooqObligations(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<RecordedObligation> listFor(ObligationFilter filter) {
        Objects.requireNonNull(filter, "filter");

        // `var`: the select carries twelve typed columns, and a `List<Record>` would need a copy of the column list
        // to be written again in the type. Every row implements `Record`, which is all the mapping needs.
        var rows = dsl.select(ID, GROUP_ID, DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR,
                        CURRENCY_CODE, CURRENCY_EXPONENT, MEMO, QUOTE_ID, INCURRED_AT, VOIDED_AT, STATUS)
                .from(OBLIGATIONS)
                .where(GROUP_ID.eq(filter.groupId()))
                .and(narrowedBy(filter))
                .orderBy(filter.sort().ascending() ? INCURRED_AT.asc() : INCURRED_AT.desc(), ID.asc())
                .limit(filter.limit())
                .fetch();

        Set<UUID> quoteIds = new HashSet<>();
        for (Record row : rows) {
            UUID quoteId = row.get("quote_id", UUID.class);
            if (quoteId != null) {
                quoteIds.add(quoteId);
            }
        }

        // One query for the whole page's quotes, after the obligations are read: a join would need a second set of
        // aliased names for the same table, and a query per row would make a page of fifty obligations fifty-one
        // queries. FxQuoteRows.byId carries the reasoning.
        Map<UUID, FxQuote> quotes = FxQuoteRows.byId(dsl, quoteIds);

        List<RecordedObligation> obligations = new ArrayList<>(rows.size());
        for (Record row : rows) {
            obligations.add(toRecorded(row, quotes));
        }

        return List.copyOf(obligations);
    }

    /**
     * The narrowings the contract declares, applied to the same status expression the response carries.
     */
    private static Condition narrowedBy(ObligationFilter filter) {
        Condition narrowed = DSL.noCondition();

        if (filter.status() != null) {
            narrowed = narrowed.and(STATUS_EXPRESSION.eq(statusName(filter.status())));
        }

        if (filter.memberId() != null) {
            // Either side: a member reading their own ledger wants what they owe and what they are owed, which is
            // why the contract's parameter is `memberId` rather than `debtorId`.
            narrowed = narrowed.and(DEBTOR_ID.eq(filter.memberId()).or(CREDITOR_ID.eq(filter.memberId())));
        }

        if (filter.from() != null) {
            narrowed = narrowed.and(INCURRED_AT.ge(startOfUtcDay(filter.from())));
        }

        if (filter.to() != null) {
            // `to` is a day, and a range asked for as two dates contains the whole of both: the upper bound is the
            // next midnight, so an obligation incurred at 18:30 on the day asked for is in the answer.
            narrowed = narrowed.and(INCURRED_AT.lt(startOfUtcDay(filter.to().plusDays(1))));
        }

        return narrowed;
    }

    private static OffsetDateTime startOfUtcDay(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
    }

    private static String statusName(ObligationStatus status) {
        return status.wireName();
    }

    /**
     * One page row as the same type the write path answers with.
     *
     * <p>The columns are read by name rather than through typed constants, which is the one place in this package
     * that does so. The reason is the derived table: the result has a single scope whose column names are the inner
     * select's aliases, so a name is unambiguous here - and the alternative is a second set of eleven qualified
     * constants that would have to be kept in step with the inner select for no safety this result does not already
     * have. The status comes from the derivation, not from the voided column, so a settled obligation reports
     * {@code settled}.
     */
    private static RecordedObligation toRecorded(Record row, Map<UUID, FxQuote> quotes) {
        Money amount = Money.of(row.get("amount_minor", Long.class),
                Currency.of(row.get("currency_code", String.class), row.get("currency_exponent", Short.class)));

        UUID quoteId = row.get("quote_id", UUID.class);
        FxQuote quote = quoteId == null ? null : quotes.get(quoteId);

        if (quoteId != null && quote == null) {
            throw new IllegalStateException(
                    "obligation " + row.get("id", UUID.class) + " refers to quote " + quoteId + ", which is not in "
                            + "fx_quotes, so it cannot be read as it stands: the quote is what says in which currency "
                            + "and at which rate the creditor was to be paid. A quote is never deleted (ADR 0004), so "
                            + "this means the row was written by hand.");
        }

        return new RecordedObligation(
                row.get("id", UUID.class),
                row.get("group_id", UUID.class),
                row.get("debtor_id", UUID.class),
                row.get("creditor_id", UUID.class),
                amount,
                quote == null ? amount.currency() : quote.convertedAmount().currency(),
                quote,
                row.get("memo", String.class),
                // The wire word, read back as the enum through the one vocabulary the enum owns. A value the database
                // holds that is not one of the four is a fault rather than an unknown status: the CHECK constraint
                // on the worker's table is what decides the run states, and this module's own column has no such word.
                ObligationStatus.fromWire(row.get(RESULT_STATUS)).orElseThrow(() -> new IllegalStateException(
                        "obligation " + row.get("id", UUID.class) + " has status '" + row.get(RESULT_STATUS)
                                + "', which is not one of " + ObligationStatus.vocabulary())),
                row.get("incurred_at", OffsetDateTime.class).toInstant());
    }
}
