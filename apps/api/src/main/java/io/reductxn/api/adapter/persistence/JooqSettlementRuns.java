package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.out.SettlementRuns;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.planning.api.FxClearing;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A run's rows, projected for reading.
 *
 * <p><strong>Four tables, four reads, one picture.</strong> {@code settlement_runs}, {@code settlement_partitions},
 * {@code settlement_transfers} and {@code settlement_decisions} all belong to {@code workers/settlement} (its
 * migrations are the {@code 5xx} range) and this module reads them as rows rather than as a dependency on the module
 * (RULE B6, RULE B17). No jOOQ type leaves this package (RULE B16), so the columns are named here and mapped to
 * {@link RunReport} inside the fetch.
 *
 * <p><strong>The transfer order is the table's own promise, not convenience.</strong> {@code V501}'s comment on
 * {@code settlement_transfers_by_run} says reading a run's plan back is "by run, in a deterministic order", and that
 * ordering by {@code rail_ref} rather than by insertion order is deliberate: "a derived reference has a stable total
 * order, while the order the rows came back in is not an order at all, and a run whose reported order changed
 * between two reads would make a plan the group approved and a plan a rail executed look different when they are the
 * same". A read model that ordered by {@code created_at} would quietly undo that.
 *
 * <p><strong>The FX label comes from {@code libs/planning}'s model of the clearing account</strong>
 * ({@code FxClearing.isClearing}, RULE B21) rather than from a copy of its sentinel id: the account is a participant
 * in the plan's arithmetic and not a member of the group, and reporting the group's FX legs as members' payments
 * would present the group's exposure as somebody's debt (ADR 0012).
 */
@Repository
public class JooqSettlementRuns implements SettlementRuns {

    private static final Table<Record> RUNS = DSL.table("settlement_runs");
    private static final Field<UUID> RUN_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> RUN_GROUP = DSL.field("group_id", UUID.class);
    private static final Field<UUID> RUN_CYCLE = DSL.field("cycle_id", UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field("status", String.class);
    private static final Field<OffsetDateTime> RUN_COMPUTED_AT = DSL.field("computed_at", OffsetDateTime.class);

    private static final Table<Record> PARTITIONS = DSL.table("settlement_partitions");
    private static final Field<UUID> PART_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> PART_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Long> PART_GROSS = DSL.field("gross_minor", Long.class);
    private static final Field<Long> PART_NET = DSL.field("net_minor", Long.class);
    private static final Field<Long> PART_TAX = DSL.field("tax_minor", Long.class);
    private static final Field<Long> PART_TAX_SAVED = DSL.field("tax_saved_minor", Long.class);

    private static final Table<Record> TRANSFERS = DSL.table("settlement_transfers");
    private static final Field<UUID> TRANSFER_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> TRANSFER_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<UUID> TRANSFER_FROM = DSL.field("from_user_id", UUID.class);
    private static final Field<UUID> TRANSFER_TO = DSL.field("to_user_id", UUID.class);
    private static final Field<Long> TRANSFER_AMOUNT = DSL.field("amount_minor", Long.class);
    private static final Field<String> TRANSFER_STATUS = DSL.field("status", String.class);
    private static final Field<String> TRANSFER_RAIL_REF = DSL.field("rail_ref", String.class);
    private static final Field<String> TRANSFER_RAIL_DETAIL = DSL.field("rail_detail", String.class);

    private static final Table<Record> DECISIONS = DSL.table("settlement_decisions");
    private static final Field<UUID> DECISION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<UUID> DECISION_MEMBER = DSL.field("member_id", UUID.class);
    private static final Field<String> DECISION_WORD = DSL.field("decision", String.class);
    private static final Field<OffsetDateTime> DECISION_AT = DSL.field("decided_at", OffsetDateTime.class);

    /**
     * What the worker's gates concluded, written by the worker and read here (ADR 0023).
     *
     * <p>Two tables rather than one: the verdict is a row per run, and what is still missing for a currency is a
     * row per currency - an amount carries a currency and an exponent, so it travels with them as every other amount
     * in this schema does (ADR 0012 point 4). Nothing here is recomputed; the arithmetic is the worker's and this
     * module only reads the answer.
     */
    private static final Table<Record> GATES = DSL.table("settlement_run_gates");
    private static final Field<UUID> GATE_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> GATE_STATUS = DSL.field("status", String.class);
    private static final Field<Boolean> GATE_QUORUM_MET = DSL.field("quorum_met", Boolean.class);
    private static final Field<Integer> GATE_APPROVALS_COUNTED = DSL.field("approvals_counted", Integer.class);
    private static final Field<Integer> GATE_APPROVALS_REQUIRED = DSL.field("approvals_required", Integer.class);
    private static final Field<Integer> GATE_DEBTORS_TO_CONFIRM =
            DSL.field("debtors_still_to_confirm", Integer.class);
    private static final Field<Boolean> GATE_FUNDING_SATISFIED = DSL.field("funding_satisfied", Boolean.class);
    private static final Field<OffsetDateTime> GATE_CONCLUDED = DSL.field("concluded_at", OffsetDateTime.class);

    private static final Table<Record> GATE_SHORTFALLS = DSL.table("settlement_run_gate_shortfalls");
    private static final Field<UUID> SHORTFALL_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> SHORTFALL_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> SHORTFALL_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<Long> SHORTFALL_AMOUNT = DSL.field("amount_minor", Long.class);

    private final DSLContext dsl;

    public JooqSettlementRuns(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<RunReport> findById(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        Record run = dsl.select(RUN_ID, RUN_GROUP, RUN_CYCLE, RUN_STATUS, RUN_COMPUTED_AT)
                .from(RUNS)
                .where(RUN_ID.eq(runId))
                .fetchOne();

        if (run == null) {
            return Optional.empty();
        }

        return Optional.of(new RunReport(
                run.get(RUN_ID),
                run.get(RUN_GROUP),
                run.get(RUN_CYCLE),
                run.get(RUN_STATUS),
                instantOf(run.get(RUN_COMPUTED_AT)),
                partitions(runId),
                transfers(runId),
                answers(runId),
                gates(runId)));
    }

    /**
     * The gates' verdict, or null when the worker has not looked at this run yet.
     *
     * <p>Null rather than a default verdict, and the distinction is the point: a run that was just computed has no
     * conclusion, and reporting "quorum not met" for it would be this module answering a question it did not ask -
     * the same fabrication {@code RunReport}'s absent {@code executedAt} refuses. A client that sees no {@code gates}
     * learns that no look has happened yet.
     */
    private RunReport.Gates gates(UUID runId) {
        Record stored = dsl.select(GATE_STATUS, GATE_QUORUM_MET, GATE_APPROVALS_COUNTED, GATE_APPROVALS_REQUIRED,
                        GATE_DEBTORS_TO_CONFIRM, GATE_FUNDING_SATISFIED, GATE_CONCLUDED)
                .from(GATES)
                .where(GATE_RUN.eq(runId))
                .fetchOne();

        if (stored == null) {
            return null;
        }

        return new RunReport.Gates(
                instantOf(stored.get(GATE_CONCLUDED)),
                stored.get(GATE_STATUS),
                new RunReport.Gates.Quorum(
                        stored.get(GATE_QUORUM_MET),
                        stored.get(GATE_APPROVALS_COUNTED),
                        stored.get(GATE_APPROVALS_REQUIRED),
                        stored.get(GATE_DEBTORS_TO_CONFIRM)),
                new RunReport.Gates.Funding(stored.get(GATE_FUNDING_SATISFIED), shortfalls(runId)));
    }

    /** The currency amounts still missing, in the order the worker wrote them. */
    private List<Money> shortfalls(UUID runId) {
        return dsl.select(SHORTFALL_CURRENCY, SHORTFALL_EXPONENT, SHORTFALL_AMOUNT)
                .from(GATE_SHORTFALLS)
                .where(SHORTFALL_RUN.eq(runId))
                .orderBy(SHORTFALL_CURRENCY)
                .fetch(shortfall -> Money.of(
                        shortfall.get(SHORTFALL_AMOUNT),
                        Currency.of(shortfall.get(SHORTFALL_CURRENCY), shortfall.get(SHORTFALL_EXPONENT))));
    }

    private static Instant instantOf(OffsetDateTime stored) {
        return stored == null ? null : stored.toInstant();
    }

    /**
     * The plan's per-currency figures, with each partition's transfer count read from the transfers themselves.
     *
     * <p>Two reads rather than a join, and the reason is not style: the two tables both have {@code run_id} and
     * {@code currency_code}, so a join here needs qualified columns in three places to say what the query already
     * says unambiguously in two. {@link ReadRunService}'s transaction is what keeps the count and the figures a
     * single view of the rows.
     */
    private List<RunReport.Partition> partitions(UUID runId) {
        Map<String, Integer> transfersPerCurrency = dsl.select(TRANSFER_CURRENCY, DSL.count())
                .from(TRANSFERS)
                .where(TRANSFER_RUN.eq(runId))
                .groupBy(TRANSFER_CURRENCY)
                .fetchMap(TRANSFER_CURRENCY, record -> record.get(DSL.count()));

        return dsl.select(PART_CURRENCY, PART_GROSS, PART_NET, PART_TAX, PART_TAX_SAVED)
                .from(PARTITIONS)
                .where(PART_RUN.eq(runId))
                .orderBy(PART_CURRENCY)
                .fetch(partition -> new RunReport.Partition(
                        partition.get(PART_CURRENCY),
                        partition.get(PART_GROSS),
                        partition.get(PART_NET),
                        partition.get(PART_TAX),
                        partition.get(PART_TAX_SAVED),
                        transfersPerCurrency.getOrDefault(partition.get(PART_CURRENCY), 0)));
    }

    private List<RunReport.Transfer> transfers(UUID runId) {
        return dsl.select(TRANSFER_FROM, TRANSFER_TO, TRANSFER_AMOUNT, TRANSFER_CURRENCY, TRANSFER_STATUS,
                        TRANSFER_RAIL_REF, TRANSFER_RAIL_DETAIL)
                .from(TRANSFERS)
                .where(TRANSFER_RUN.eq(runId))
                .orderBy(TRANSFER_RAIL_REF)
                .fetch(transfer -> {
                    UUID to = transfer.get(TRANSFER_TO);

                    return new RunReport.Transfer(
                            transfer.get(TRANSFER_FROM),
                            to,
                            FxClearing.isClearing(to)
                                    ? RunReport.ToAccount.FX_CLEARING
                                    : RunReport.ToAccount.MEMBER,
                            transfer.get(TRANSFER_AMOUNT),
                            transfer.get(TRANSFER_CURRENCY),
                            transfer.get(TRANSFER_STATUS),
                            transfer.get(TRANSFER_RAIL_REF),
                            transfer.get(TRANSFER_RAIL_DETAIL));
                });
    }

    private List<RunReport.Answer> answers(UUID runId) {
        List<RunReport.Answer> answers = new ArrayList<>();

        dsl.select(DECISION_MEMBER, DECISION_WORD, DECISION_AT)
                .from(DECISIONS)
                .where(DECISION_RUN.eq(runId))
                .orderBy(DECISION_AT, DECISION_MEMBER)
                .fetch(decision -> answers.add(new RunReport.Answer(
                        decision.get(DECISION_MEMBER),
                        decision.get(DECISION_WORD),
                        instantOf(decision.get(DECISION_AT)))));

        return answers;
    }
}
