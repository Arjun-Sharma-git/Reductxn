package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
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
import java.util.List;
import java.util.Objects;
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
 * <p>Ordering is by {@code (incurred_at, id)}: the planner normalises order itself, but a stable order
 * makes a log or a failing test reproducible, and an unstable one makes both worthless.
 */
@Component
public class JooqOpenObligations implements OpenObligations {

    private static final Table<Record> OBLIGATIONS = DSL.table("obligations");
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<UUID> DEBTOR_ID = DSL.field("debtor_id", UUID.class);
    private static final Field<UUID> CREDITOR_ID = DSL.field("creditor_id", UUID.class);
    private static final Field<Long> AMOUNT_MINOR = DSL.field("amount_minor", Long.class);
    private static final Field<String> CURRENCY_CODE = DSL.field("currency_code", String.class);
    private static final Field<Short> CURRENCY_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<OffsetDateTime> INCURRED_AT =
            DSL.field("incurred_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> VOIDED_AT = DSL.field("voided_at", OffsetDateTime.class);

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

        return dsl.select(DEBTOR_ID, CREDITOR_ID, AMOUNT_MINOR, CURRENCY_CODE, CURRENCY_EXPONENT)
                .from(OBLIGATIONS)
                .where(GROUP_ID.eq(groupId))
                .and(INCURRED_AT.le(asOfUtc))
                .and(VOIDED_AT.isNull().or(VOIDED_AT.gt(asOfUtc)))
                .orderBy(INCURRED_AT.asc(), ID.asc())
                .fetch(JooqOpenObligations::toObligation);
    }

    /**
     * One row as the planner's type.
     *
     * <p>The exponent travels with the amount rather than being looked up, so a historical obligation means
     * the same thing it meant when it was recorded.
     */
    private static CycleObligation toObligation(Record row) {
        Currency currency = Currency.of(row.get(CURRENCY_CODE), row.get(CURRENCY_EXPONENT));

        return new SingleCurrencyObligation(
                row.get(DEBTOR_ID),
                row.get(CREDITOR_ID),
                Money.of(row.get(AMOUNT_MINOR), currency));
    }
}
