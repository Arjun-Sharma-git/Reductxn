package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.TaxReportLine;
import io.reductxn.api.port.out.TaxTotals;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The plan's tax figures, summed per currency over a range of cycle instants.
 *
 * <p><strong>It reads the stored plan rather than re-running the engine.</strong> {@code settlement_partitions} holds
 * the numbers the run computed, and its own migration says why: the plan's gross, its net, its tax and its tax saving
 * are {@code libs/netting}'s answers about the partition, so re-deriving them here would mean netting the cycle again
 * to read a run - and a report that ran the engine could disagree with the run it is reporting on. This is the same
 * reason the runs read projects those columns rather than recomputing them.
 *
 * <p><strong>Two exclusions, and both are readings rather than filters.</strong> A run that was {@code rejected} is a
 * plan the group refused, and one that {@code failed} is a plan that was attempted and did not complete: neither
 * happened, so neither cost anything. That is exactly what the obligations list says about a run that ended without
 * settling - it releases what it held (ADR 0036) - and a tax report that counted a refused plan would be reporting a
 * liability the group already declined.
 *
 * <p><strong>The period is the cycle's {@code as_of}, not the run's creation.</strong> {@code as_of} is the instant the
 * books were closed and the instant every rate and tax rule was resolved at, so it is the date a tax belongs to; a run
 * computed today for a cycle closed last quarter is last quarter's tax. The comparison is half-open, as the port says.
 *
 * <p><strong>Grouped by currency <em>and</em> exponent.</strong> Two exponents for one currency code is incoherent -
 * one currency cannot have two minor units in one cycle, which is what {@code SettlementPlan} refuses at planning time
 * - and if a corrupt pair ever reaches this table, two lines are how a reader sees it. Grouping by code alone would
 * merge them into one line whose amounts have no single meaning.
 */
@Component
public class JooqTaxTotals implements TaxTotals {

    /** Aliased, because all three tables carry an `id` and two carry a currency column. */
    private static final Table<?> RUNS = DSL.table("settlement_runs").as("r");
    private static final Table<?> CYCLES = DSL.table("settlement_cycles").as("c");
    private static final Table<?> PARTITIONS = DSL.table("settlement_partitions").as("p");

    private static final Field<UUID> RUN_ID = DSL.field(DSL.name("r", "id"), UUID.class);
    private static final Field<UUID> RUN_GROUP = DSL.field(DSL.name("r", "group_id"), UUID.class);
    private static final Field<UUID> RUN_CYCLE = DSL.field(DSL.name("r", "cycle_id"), UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field(DSL.name("r", "status"), String.class);

    private static final Field<UUID> CYCLE_ID = DSL.field(DSL.name("c", "id"), UUID.class);
    private static final Field<OffsetDateTime> CYCLE_AS_OF =
            DSL.field(DSL.name("c", "as_of"), OffsetDateTime.class);

    private static final Field<UUID> PART_RUN = DSL.field(DSL.name("p", "run_id"), UUID.class);
    private static final Field<String> PART_CURRENCY = DSL.field(DSL.name("p", "currency_code"), String.class);
    private static final Field<Short> PART_EXPONENT = DSL.field(DSL.name("p", "currency_exponent"), Short.class);
    private static final Field<Long> PART_TAX = DSL.field(DSL.name("p", "tax_minor"), Long.class);
    private static final Field<Long> PART_TAX_SAVED = DSL.field(DSL.name("p", "tax_saved_minor"), Long.class);

    /** The two states that mean the plan did not happen (see the class comment). */
    private static final List<String> PLANS_THAT_DID_NOT_HAPPEN = List.of("rejected", "failed");

    private static final Field<BigDecimal> TAX_TOTAL = DSL.sum(PART_TAX);
    private static final Field<BigDecimal> TAX_SAVED_TOTAL = DSL.sum(PART_TAX_SAVED);
    private static final Field<Integer> RUNS_COUNTED = DSL.count();

    private final DSLContext dsl;

    public JooqTaxTotals(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public List<TaxReportLine> totals(UUID groupId, Instant from, Instant to) {
        Objects.requireNonNull(groupId, "groupId");

        return dsl.select(PART_CURRENCY, PART_EXPONENT, TAX_TOTAL, TAX_SAVED_TOTAL, RUNS_COUNTED)
                .from(PARTITIONS)
                .join(RUNS).on(PART_RUN.eq(RUN_ID))
                .join(CYCLES).on(RUN_CYCLE.eq(CYCLE_ID))
                .where(RUN_GROUP.eq(groupId))
                .and(RUN_STATUS.notIn(PLANS_THAT_DID_NOT_HAPPEN))
                .and(from == null ? DSL.noCondition() : CYCLE_AS_OF.ge(from.atOffset(ZoneOffset.UTC)))
                .and(to == null ? DSL.noCondition() : CYCLE_AS_OF.lt(to.atOffset(ZoneOffset.UTC)))
                .groupBy(PART_CURRENCY, PART_EXPONENT)
                .orderBy(PART_CURRENCY, PART_EXPONENT)
                .fetch(line -> TaxReportLine.of(
                        line.get(PART_CURRENCY),
                        integral(line.get(TAX_TOTAL)),
                        integral(line.get(TAX_SAVED_TOTAL)),
                        line.get(RUNS_COUNTED)));
    }

    /**
     * A summed {@code bigint}, exactly as a long.
     *
     * <p>Postgres sums {@code bigint} as {@code numeric}, so the driver hands back a {@link BigDecimal} that is always
     * integral - and {@code longValueExact} is what says so rather than assuming it. It would fail only for a total
     * beyond 9.2 quintillion minor units, which no group will reach; failing loudly is still better than a silently
     * truncated tax figure.
     */
    private static long integral(BigDecimal summed) {
        return summed == null ? 0L : summed.longValueExact();
    }
}
