package io.reductxn.settlement.adapter.persistence;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.TransferInstruction;
import io.reductxn.planning.api.CurrencyPlan;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.Funding;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.GateConclusion;
import io.reductxn.settlement.port.in.PartitionOutcome;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.Quorum;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.SettlementRun;
import io.reductxn.settlement.port.in.StuckTransfer;
import io.reductxn.settlement.port.in.TransferStatus;
import io.reductxn.settlement.port.out.Runs;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The runs, in this module's five tables.
 *
 * <p>Writes the plan as it was handed over and reads it back for the answer, so there is one derivation of the
 * report rather than two - and it is exercised on the way out of every write. Everything read here is a row:
 * nothing re-runs the engine to describe a run, because a run is a record of what was decided rather than a
 * question to be asked again.
 *
 * <p>The rail references are derived from each transfer's own coordinates, which is RULE S2's requirement that
 * a re-submission be a no-op. They belong in a rail adapter, and there is none yet - so the derivation lives
 * here, in one place, with the schema's {@code UNIQUE} constraint as its second half. When {@code libs/rails}
 * arrives this is where its first caller will find the format.
 */
@Component
public class JooqRuns implements Runs {

    private static final Table<Record> CYCLES = DSL.table("settlement_cycles");
    private static final Table<Record> RUNS = DSL.table("settlement_runs");
    private static final Table<Record> PARTITIONS = DSL.table("settlement_partitions");
    private static final Table<Record> POSITIONS = DSL.table("net_positions");
    private static final Table<Record> TRANSFERS = DSL.table("settlement_transfers");

    private static final Field<UUID> CYCLE_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> CYCLE_GROUP = DSL.field("group_id", UUID.class);
    private static final Field<OffsetDateTime> CYCLE_AS_OF = DSL.field("as_of", OffsetDateTime.class);
    private static final Field<OffsetDateTime> CYCLE_CREATED = DSL.field("created_at", OffsetDateTime.class);

    private static final Field<UUID> RUN_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> RUN_GROUP = DSL.field("group_id", UUID.class);
    private static final Field<UUID> RUN_CYCLE = DSL.field("cycle_id", UUID.class);
    private static final Field<String> RUN_STATUS = DSL.field("status", String.class);
    private static final Field<OffsetDateTime> RUN_COMPUTED = DSL.field("computed_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> RUN_CREATED = DSL.field("created_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> RUN_DRIVEN = DSL.field("driven_at", OffsetDateTime.class);
    // The due query joins runs to their cycles, so both tables are aliased and every column is qualified: the
    // three names they have in common (`id`, `group_id`, `created_at`) are exactly the ones an unqualified field
    // would resolve against the wrong table, or fail to resolve at all. JooqChainHeads sets out the reasoning
    // for writing qualifiers out rather than relying on DSL.field(String, Class).
    private static final Table<Record> DUE_RUN = DSL.table("settlement_runs").as("due_run");
    private static final Table<Record> DUE_CYCLE = DSL.table("settlement_cycles").as("due_cycle");

    private static final Field<UUID> DUE_RUN_ID = DSL.field(DSL.name("due_run", "id"), UUID.class);
    private static final Field<String> DUE_RUN_STATUS = DSL.field(DSL.name("due_run", "status"), String.class);
    private static final Field<OffsetDateTime> DUE_RUN_COMPUTED =
            DSL.field(DSL.name("due_run", "computed_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> DUE_RUN_CREATED =
            DSL.field(DSL.name("due_run", "created_at"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> DUE_RUN_DRIVEN =
            DSL.field(DSL.name("due_run", "driven_at"), OffsetDateTime.class);
    private static final Field<UUID> DUE_RUN_CYCLE = DSL.field(DSL.name("due_run", "cycle_id"), UUID.class);

    private static final Field<UUID> DUE_CYCLE_ID = DSL.field(DSL.name("due_cycle", "id"), UUID.class);
    private static final Field<UUID> DUE_CYCLE_GROUP = DSL.field(DSL.name("due_cycle", "group_id"), UUID.class);
    private static final Field<OffsetDateTime> DUE_CYCLE_AS_OF =
            DSL.field(DSL.name("due_cycle", "as_of"), OffsetDateTime.class);
    private static final Field<OffsetDateTime> DUE_CYCLE_CREATED =
            DSL.field(DSL.name("due_cycle", "created_at"), OffsetDateTime.class);

    /** The states a driver can act on, as {@code RunStatus.wireName()} spells them. */
    private static final List<String> DRIVEABLE = List.of(
            RunStatus.AWAITING_APPROVAL.wireName(),
            RunStatus.AWAITING_FX_FUNDING.wireName(),
            RunStatus.EXECUTING.wireName());

    /** The states a transfer never moves out of, so a run whose transfers are all here has nothing to attempt. */
    private static final List<String> RESOLVED = List.of(
            TransferStatus.CONFIRMED.wireName(), TransferStatus.FAILED.wireName());

    private static final Field<UUID> PARTITION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> PARTITION_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> PARTITION_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<Long> PARTITION_GROSS = DSL.field("gross_minor", Long.class);
    private static final Field<Long> PARTITION_NET = DSL.field("net_minor", Long.class);
    private static final Field<Long> PARTITION_TAX = DSL.field("tax_minor", Long.class);
    private static final Field<Long> PARTITION_TAX_SAVED = DSL.field("tax_saved_minor", Long.class);

    private static final Field<UUID> POSITION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> POSITION_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> POSITION_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<UUID> POSITION_PARTICIPANT = DSL.field("participant_id", UUID.class);
    private static final Field<Long> POSITION_NET = DSL.field("net_minor", Long.class);

    private static final Field<UUID> TRANSFER_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> TRANSFER_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> TRANSFER_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> TRANSFER_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<UUID> TRANSFER_FROM = DSL.field("from_user_id", UUID.class);
    private static final Field<UUID> TRANSFER_TO = DSL.field("to_user_id", UUID.class);
    private static final Field<Long> TRANSFER_AMOUNT = DSL.field("amount_minor", Long.class);
    private static final Field<String> TRANSFER_RAIL_REF = DSL.field("rail_ref", String.class);
    private static final Field<String> TRANSFER_STATUS = DSL.field("status", String.class);
    private static final Field<String> TRANSFER_DETAIL = DSL.field("rail_detail", String.class);
    private static final Field<Integer> TRANSFER_UNANSWERED = DSL.field("unanswered_attempts", Integer.class);
    private static final Field<OffsetDateTime> TRANSFER_NEXT_ATTEMPT =
            DSL.field("next_attempt_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> TRANSFER_UNANSWERED_SINCE =
            DSL.field("unanswered_since", OffsetDateTime.class);
    // When a rail accepted the transfer, for the reconciliation half of the report (V510). Null means no rail ever
    // took it, which is a different problem with a different report line.
    private static final Field<OffsetDateTime> TRANSFER_SUBMITTED_AT =
            DSL.field("submitted_at", OffsetDateTime.class);
    // Who decided a transfer when it was not a rail that did (V509). Kept beside the columns above rather than in a
    // table of their own: they are three facts about one transfer row, written by one statement.
    private static final Field<String> TRANSFER_RESOLVED_BY = DSL.field("resolved_by", String.class);
    private static final Field<String> TRANSFER_RESOLVED_BASIS = DSL.field("resolved_basis", String.class);
    private static final Field<OffsetDateTime> TRANSFER_RESOLVED_AT = DSL.field("resolved_at", OffsetDateTime.class);

    /**
     * The three transfer columns whose names {@code settlement_runs} also has, written out qualified.
     *
     * <p>They are needed only by the stuck-transfer report, which joins the two tables; every other read here looks at
     * one table and can use the unqualified fields above. `id`, `run_id` and `status` each exist in both, and an
     * unqualified one of those is a query Postgres refuses - or worse, one it accepts against the wrong table.
     * `JooqChainHeads` sets out the same reasoning for its own join.
     */
    private static final Field<UUID> STUCK_TRANSFER_ID =
            DSL.field(DSL.name("settlement_transfers", "id"), UUID.class);
    private static final Field<UUID> STUCK_TRANSFER_RUN =
            DSL.field(DSL.name("settlement_transfers", "run_id"), UUID.class);
    private static final Field<String> STUCK_TRANSFER_STATUS =
            DSL.field(DSL.name("settlement_transfers", "status"), String.class);
    private static final Field<UUID> STUCK_RUN_ID = DSL.field(DSL.name("settlement_runs", "id"), UUID.class);
    private static final Field<OffsetDateTime> TRANSFER_CREATED = DSL.field("created_at", OffsetDateTime.class);

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

    private static final Table<Record> RUN_OBLIGATIONS = DSL.table("settlement_run_obligations");
    private static final Field<UUID> RUN_OBLIGATION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<UUID> RUN_OBLIGATION_ID = DSL.field("obligation_id", UUID.class);

    private static final Table<Record> DECISIONS = DSL.table("settlement_decisions");
    private static final Field<UUID> DECISION_RUN = DSL.field("run_id", UUID.class);
    private static final Field<UUID> DECISION_MEMBER = DSL.field("member_id", UUID.class);
    private static final Field<String> DECISION_VALUE = DSL.field("decision", String.class);
    private static final Field<OffsetDateTime> DECISION_AT = DSL.field("decided_at", OffsetDateTime.class);

    private static final Table<Record> FUNDINGS = DSL.table("settlement_fx_fundings");
    private static final Field<UUID> FUNDING_ID = DSL.field("id", UUID.class);
    private static final Field<UUID> FUNDING_RUN = DSL.field("run_id", UUID.class);
    private static final Field<String> FUNDING_CURRENCY = DSL.field("currency_code", String.class);
    private static final Field<Short> FUNDING_EXPONENT = DSL.field("currency_exponent", Short.class);
    private static final Field<Long> FUNDING_AMOUNT = DSL.field("amount_minor", Long.class);
    private static final Field<String> FUNDING_REF = DSL.field("funding_ref", String.class);
    private static final Field<OffsetDateTime> FUNDING_AT = DSL.field("funded_at", OffsetDateTime.class);

    private static final String PENDING = "pending";

    private final DSLContext dsl;

    public JooqRuns(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<ComputedRun> findRun(UUID groupId, Instant asOf) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        Record cycle = dsl.select(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .from(CYCLES)
                .where(CYCLE_GROUP.eq(groupId))
                .and(CYCLE_AS_OF.eq(asOf.atOffset(ZoneOffset.UTC)))
                .fetchOne();

        return cycle == null ? Optional.empty() : describe(cycle);
    }

    @Override
    public ComputedRun open(SettlementRun run, SettlementPlan plan, List<UUID> settledObligationIds) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(settledObligationIds, "settledObligationIds");

        OffsetDateTime asOf = run.asOf().atOffset(ZoneOffset.UTC);

        // The cycle first, and `do nothing` on conflict rather than a check-then-insert: two processes
        // computing the same cycle at once is precisely the race the derived ids cannot settle, and this is the
        // statement that settles it. The loser reads the winner's cycle and run - which is why `findRun` is the
        // recovery path rather than a wish.
        dsl.insertInto(CYCLES)
                .columns(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .values(run.cycleId(), run.groupId(), asOf, run.createdAt().atOffset(ZoneOffset.UTC))
                .onConflict(CYCLE_GROUP, CYCLE_AS_OF)
                .doNothing()
                .execute();

        UUID cycleId = dsl.select(CYCLE_ID)
                .from(CYCLES)
                .where(CYCLE_GROUP.eq(run.groupId()))
                .and(CYCLE_AS_OF.eq(asOf))
                .fetchOne(CYCLE_ID);

        dsl.insertInto(RUNS)
                .columns(RUN_ID, RUN_GROUP, RUN_CYCLE, RUN_STATUS, RUN_COMPUTED, RUN_CREATED)
                .values(run.id(), run.groupId(), cycleId, run.status().wireName(),
                        run.computedAt().atOffset(ZoneOffset.UTC), run.createdAt().atOffset(ZoneOffset.UTC))
                .onConflict(RUN_CYCLE)
                .doNothing()
                .execute();

        for (CurrencyPlan partition : plan.partitions()) {
            writePartition(run, partition);
        }

        // Which commitments this run covers, written with the plan it was computed from. A run and the
        // promises it settles are one fact: a run without them could not answer what it discharged, and a
        // later cycle would net the same obligations again.
        for (UUID obligationId : settledObligationIds) {
            dsl.insertInto(RUN_OBLIGATIONS)
                    .columns(RUN_OBLIGATION_RUN, RUN_OBLIGATION_ID)
                    .values(run.id(), obligationId)
                    // `do nothing` because the run's own insert above conflicts when a second attempt finds a
                    // run that already exists - in which case its links exist too, and re-inserting them would
                    // fail on the transaction the retry is supposed to survive.
                    .onConflict(RUN_OBLIGATION_RUN, RUN_OBLIGATION_ID)
                    .doNothing()
                    .execute();
        }

        return findRun(run.groupId(), run.asOf())
                .orElseThrow(() -> new IllegalStateException(
                        "run " + run.id() + " was opened and cannot be read back. The write and the read are "
                                + "in one transaction, so this means the rows went somewhere else"));
    }

    @Override
    public Optional<ComputedRun> findRunById(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        Record runRow = dsl.select(RUN_CYCLE)
                .from(RUNS)
                .where(RUN_ID.eq(runId))
                .fetchOne();

        if (runRow == null) {
            return Optional.empty();
        }

        Record cycle = dsl.select(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .from(CYCLES)
                .where(CYCLE_ID.eq(runRow.get(RUN_CYCLE)))
                .fetchOne();

        if (cycle == null) {
            throw new IllegalStateException(
                    "run " + runId + " refers to cycle " + runRow.get(RUN_CYCLE) + ", which is not in "
                            + "settlement_cycles. The two are written together, so this means a cycle row was "
                            + "deleted rather than that a run was opened without one");
        }

        return Optional.of(describe(cycle)
                .orElseThrow(() -> new IllegalStateException(
                        "run " + runId + " was found by its id and its cycle " + runRow.get(RUN_CYCLE)
                                + " describes no run. The run's own row was read in this transaction, so this "
                                + "means the rows went somewhere else between the two queries")));
    }

    @Override
    public List<Runs.OpenedCycle> cyclesWithoutRuns(int batchSize) {
        return dsl.select(DUE_CYCLE_ID, DUE_CYCLE_GROUP, DUE_CYCLE_AS_OF, DUE_CYCLE_CREATED)
                .from(DUE_CYCLE)
                // A left join rather than `not exists`, because the answer is a cycle and every column of it
                // comes from the cycle: this is the shape that says "these cycles and, for each, is there a
                // run" without a second query per row.
                .leftJoin(DUE_RUN).on(DUE_RUN_CYCLE.eq(DUE_CYCLE_ID))
                .where(DUE_RUN_ID.isNull())
                // Oldest first: these are requests, and a queue is served in the order it arrived. See the port's
                // javadoc for why the anti-starvation mark `dueForDriving` uses is not available here.
                .orderBy(DUE_CYCLE_CREATED.asc(), DUE_CYCLE_ID.asc())
                .limit(batchSize)
                .fetch(row -> new Runs.OpenedCycle(
                        row.get(DUE_CYCLE_ID),
                        row.get(DUE_CYCLE_GROUP),
                        row.get(DUE_CYCLE_AS_OF).toInstant(),
                        row.get(DUE_CYCLE_CREATED).toInstant()));
    }

    @Override
    public boolean openCycle(UUID cycleId, UUID groupId, Instant asOf, Instant at) {
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(at, "at");

        // `do nothing` on conflict, and the count of what it did is the answer: the same insert `open` makes
        // when it computes, so the two paths are one statement about one row. Zero rows means the cycle was
        // already open, which is not an error - it is what the caller asked for.
        return dsl.insertInto(CYCLES)
                .columns(CYCLE_ID, CYCLE_GROUP, CYCLE_AS_OF, CYCLE_CREATED)
                .values(cycleId, groupId, asOf.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC))
                .onConflict(CYCLE_GROUP, CYCLE_AS_OF)
                .doNothing()
                .execute() > 0;
    }

    @Override
    public List<SettlementRun> dueForDriving(Instant now, Instant attemptedBefore, int batchSize) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(attemptedBefore, "attemptedBefore");

        return dsl.select(DUE_RUN_ID, DUE_RUN_STATUS, DUE_RUN_COMPUTED, DUE_RUN_CREATED, DUE_CYCLE_ID,
                        DUE_CYCLE_GROUP, DUE_CYCLE_AS_OF)
                .from(DUE_RUN)
                .join(DUE_CYCLE).on(DUE_CYCLE_ID.eq(DUE_RUN_CYCLE))
                .where(DUE_RUN_STATUS.in(DRIVEABLE))
                // Null means never attempted, and it is due: a run opened a second ago has never been offered to
                // a rail. `attemptedBefore` is the window's cut-off, computed by the caller.
                .and(DUE_RUN_DRIVEN.isNull().or(DUE_RUN_DRIVEN.lt(attemptedBefore.atOffset(ZoneOffset.UTC))))
                // And something in it has to be attemptable: a run whose every unresolved transfer is waiting out
                // a backoff has nothing to try, and driving it would mark it as attempted every window while
                // nothing happened - a pass that reports "1 run moved" for a run standing still. This is the
                // transfer-level wait seen from the run's side, and it is why this query takes two instants: one
                // for the run's lease, one for the transfers' readiness.
                .and(DSL.exists(dsl.selectOne()
                        .from(TRANSFERS)
                        .where(TRANSFER_RUN.eq(DUE_RUN_ID))
                        .and(TRANSFER_STATUS.notIn(RESOLVED))
                        .and(TRANSFER_NEXT_ATTEMPT.isNull()
                                .or(TRANSFER_NEXT_ATTEMPT.le(now.atOffset(ZoneOffset.UTC))))))
                // Least recently attempted first, nulls first: a run a gate keeps refusing goes to the back of
                // the queue, which is what keeps a backlog of waiting runs from starving one that is ready.
                .orderBy(DUE_RUN_DRIVEN.asc().nullsFirst(), DUE_RUN_CREATED.asc())
                .limit(batchSize)
                .fetch(row -> new SettlementRun(
                        row.get(DUE_RUN_ID),
                        row.get(DUE_CYCLE_GROUP),
                        row.get(DUE_CYCLE_ID),
                        RunStatus.ofWireName(row.get(DUE_RUN_STATUS)),
                        row.get(DUE_CYCLE_AS_OF).toInstant(),
                        row.get(DUE_RUN_COMPUTED).toInstant(),
                        row.get(DUE_RUN_CREATED).toInstant()));
    }

    @Override
    public void markDriven(UUID runId, Instant attemptedAt) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(attemptedAt, "attemptedAt");

        dsl.update(RUNS)
                .set(RUN_DRIVEN, attemptedAt.atOffset(ZoneOffset.UTC))
                .where(RUN_ID.eq(runId))
                .execute();
    }

    @Override
    public void updateStatus(SettlementRun run) {
        Objects.requireNonNull(run, "run");

        dsl.update(RUNS)
                .set(RUN_STATUS, run.status().wireName())
                .where(RUN_ID.eq(run.id()))
                .execute();
    }

    @Override
    public void recordDecision(RunDecision decision, Instant decidedAt) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(decidedAt, "decidedAt");

        // A plain insert: the primary key on `(run_id, member_id)` is what makes a second decision impossible,
        // and swallowing the conflict here would hide the race the use case's guard could not close.
        dsl.insertInto(DECISIONS)
                .columns(DECISION_RUN, DECISION_MEMBER, DECISION_VALUE, DECISION_AT)
                .values(decision.runId(), decision.memberId(), decision.decision().wireName(),
                        decidedAt.atOffset(ZoneOffset.UTC))
                .execute();
    }

    @Override
    public void recordFunding(FundingRequest request, Instant fundedAt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(fundedAt, "fundedAt");

        // `do nothing` on the reference, because the reference is the caller's idempotency key (RULE S2): a
        // retry after a timeout records one funding rather than a second arrival that never happened.
        dsl.insertInto(FUNDINGS)
                .columns(FUNDING_ID, FUNDING_RUN, FUNDING_CURRENCY, FUNDING_EXPONENT, FUNDING_AMOUNT, FUNDING_REF,
                        FUNDING_AT)
                .values(fundingIdOf(request.runId(), request.fundingRef()), request.runId(),
                        request.currency().code(), (short) request.currency().minorUnitExponent(),
                        request.amount().amountMinor(), request.fundingRef(), fundedAt.atOffset(ZoneOffset.UTC))
                .onConflict(FUNDING_REF)
                .doNothing()
                .execute();
    }

    @Override
    public void recordGates(GateConclusion conclusion) {
        Objects.requireNonNull(conclusion, "conclusion");

        GateConclusion.QuorumVerdict quorum = conclusion.quorum();

        // Upsert on the run, because a conclusion is replaced in place rather than accumulated: one row per run,
        // carrying the version of the run it was reached for - which is why the status and the instant are in the
        // update set too, and why this write is unconditional rather than skipped when the verdict is unchanged.
        dsl.insertInto(GATES)
                .columns(GATE_RUN, GATE_STATUS, GATE_QUORUM_MET, GATE_APPROVALS_COUNTED, GATE_APPROVALS_REQUIRED,
                        GATE_DEBTORS_TO_CONFIRM, GATE_FUNDING_SATISFIED, GATE_CONCLUDED)
                .values(conclusion.runId(), conclusion.status().wireName(), quorum.met(), quorum.counted(),
                        quorum.required(), quorum.debtorsStillToConfirm(), conclusion.funding().satisfied(),
                        conclusion.concludedAt().atOffset(ZoneOffset.UTC))
                .onConflict(GATE_RUN)
                .doUpdate()
                .set(GATE_STATUS, conclusion.status().wireName())
                .set(GATE_QUORUM_MET, quorum.met())
                .set(GATE_APPROVALS_COUNTED, quorum.counted())
                .set(GATE_APPROVALS_REQUIRED, quorum.required())
                .set(GATE_DEBTORS_TO_CONFIRM, quorum.debtorsStillToConfirm())
                .set(GATE_FUNDING_SATISFIED, conclusion.funding().satisfied())
                .set(GATE_CONCLUDED, conclusion.concludedAt().atOffset(ZoneOffset.UTC))
                .execute();

        // The gaps are replaced rather than merged: a currency that has since been covered must stop being
        // reported as a gap, and an insert-only write would leave the run claiming a shortfall it no longer has.
        dsl.deleteFrom(GATE_SHORTFALLS)
                .where(SHORTFALL_RUN.eq(conclusion.runId()))
                .execute();

        for (Money outstanding : conclusion.funding().outstanding()) {
            dsl.insertInto(GATE_SHORTFALLS)
                    .columns(SHORTFALL_RUN, SHORTFALL_CURRENCY, SHORTFALL_EXPONENT, SHORTFALL_AMOUNT)
                    .values(conclusion.runId(), outstanding.currency().code(),
                            (short) outstanding.currency().minorUnitExponent(), outstanding.amountMinor())
                    .execute();
        }
    }

    @Override
    public void markTransfer(UUID transferId, TransferStatus status, String railDetail, Instant at) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");

        // An answer: the transfer's state, and the retry counter back to zero because the rail is working. The
        // wait is cleared with it - a transfer that has an answer is not waiting for anything - and so is the
        // spell of silence, which is over: a rail that has just spoken is not a rail nobody has heard from.
        var update = dsl.update(TRANSFERS)
                .set(TRANSFER_STATUS, status.wireName())
                .set(TRANSFER_DETAIL, railDetail)
                .set(TRANSFER_UNANSWERED, 0)
                .set(TRANSFER_NEXT_ATTEMPT, (OffsetDateTime) null)
                .set(TRANSFER_UNANSWERED_SINCE, (OffsetDateTime) null);

        // The rail has it, so the wait for a confirmation starts here - and only here. `coalesce` is the whole point
        // (V510): a later poll that answers "still pending" comes through this same method, and an age measured from
        // the most recent poll would restart every fifteen minutes, which is exactly how a transfer at the rail for a
        // week looked healthy. Written only when the rail says it has the transfer, so a transfer confirmed on the
        // first call keeps a null - it was never in flight.
        if (status == TransferStatus.SUBMITTED) {
            update = update.set(TRANSFER_SUBMITTED_AT, DSL.coalesce(TRANSFER_SUBMITTED_AT, at.atOffset(ZoneOffset.UTC)));
        }

        update.where(TRANSFER_ID.eq(transferId)).execute();
    }

    @Override
    public void resolveTransfer(UUID transferId, TransferStatus status, Instant at, String by, String basis) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(by, "by");
        Objects.requireNonNull(basis, "basis");

        // The same shape as `markTransfer`: an answer arrived, so the state is written, the retry counter goes back to
        // zero and the wait and the spell of silence are over. What differs is where the answer came from, so it is
        // recorded in the three columns of its own - and `rail_detail` is left untouched, because it is the last thing
        // the rail said rather than a claim about who decided.
        dsl.update(TRANSFERS)
                .set(TRANSFER_STATUS, status.wireName())
                .set(TRANSFER_UNANSWERED, 0)
                .set(TRANSFER_NEXT_ATTEMPT, (OffsetDateTime) null)
                .set(TRANSFER_UNANSWERED_SINCE, (OffsetDateTime) null)
                .set(TRANSFER_RESOLVED_BY, by)
                .set(TRANSFER_RESOLVED_BASIS, basis)
                .set(TRANSFER_RESOLVED_AT, at.atOffset(ZoneOffset.UTC))
                .where(TRANSFER_ID.eq(transferId))
                .execute();
    }

    @Override
    public void markTransferAttempted(UUID transferId, TransferStatus status, Instant at) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");

        // No counter and no wait: this write says what we are about to do, not what happened. The detail is
        // cleared because it describes the previous attempt, and a stale reason beside a fresh attempt would be
        // read as this one's.
        dsl.update(TRANSFERS)
                .set(TRANSFER_STATUS, status.wireName())
                .set(TRANSFER_DETAIL, (String) null)
                .where(TRANSFER_ID.eq(transferId))
                .execute();
    }

    @Override
    public void markTransferUnanswered(UUID transferId, TransferStatus status, String railDetail, Instant at,
            int consecutiveUnanswered, Instant nextAttemptAt, Instant unansweredSince) {

        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(unansweredSince, "unansweredSince");

        // The spell's start is written as the caller passes it, which is the start it already had when this call is
        // not the first of a spell. Attempting a transfer does *not* clear it (see `markTransferAttempted`): a
        // second offer to a rail that is still silent is part of the same silence, and clearing it here would make
        // every escalation duration at most one backoff long.
        dsl.update(TRANSFERS)
                .set(TRANSFER_STATUS, status.wireName())
                .set(TRANSFER_DETAIL, railDetail)
                .set(TRANSFER_UNANSWERED, consecutiveUnanswered)
                .set(TRANSFER_NEXT_ATTEMPT, nextAttemptAt.atOffset(ZoneOffset.UTC))
                .set(TRANSFER_UNANSWERED_SINCE, unansweredSince.atOffset(ZoneOffset.UTC))
                .where(TRANSFER_ID.eq(transferId))
                .execute();
    }

    @Override
    public List<StuckTransfer> findStuckTransfers(Instant unansweredCutOff, Instant unconfirmedCutOff, int limit) {
        Objects.requireNonNull(unansweredCutOff, "unansweredCutOff");
        Objects.requireNonNull(unconfirmedCutOff, "unconfirmedCutOff");

        // Joined to the run for its group, because the report is about somebody's money, and one query rather than a
        // lookup per row: a bad month should not make the cost of reporting it a function of how bad it was.
        //
        // `id` and `run_id` are the two names this table shares with `settlement_runs`, so those two are written out
        // qualified (`STUCK_TRANSFER_ID`/`STUCK_TRANSFER_RUN`) and the rest are unambiguous. JooqChainHeads sets
        // out why a name that resolves against the wrong table is worth the extra characters.
        //
        // Ordered by the instant the report measures (`StuckTransfer.stuckSince`), which is the same expression
        // `stuck(...)` compares against: a transfer at the rail is measured from its acceptance, and one the rail
        // never took from its silence. A test pins the two together, so an adapter that ordered by something else
        // would fail rather than quietly showing a person the wrong thing first.
        return dsl.select(STUCK_TRANSFER_ID, STUCK_TRANSFER_RUN, TRANSFER_RAIL_REF, TRANSFER_CURRENCY,
                        TRANSFER_EXPONENT, TRANSFER_AMOUNT, STUCK_TRANSFER_STATUS, TRANSFER_DETAIL,
                        TRANSFER_UNANSWERED, TRANSFER_SUBMITTED_AT, TRANSFER_UNANSWERED_SINCE, RUN_GROUP)
                .from(TRANSFERS)
                .join(RUNS).on(STUCK_RUN_ID.eq(STUCK_TRANSFER_RUN))
                .where(stuck(unansweredCutOff, unconfirmedCutOff))
                .orderBy(DSL.coalesce(TRANSFER_SUBMITTED_AT, TRANSFER_UNANSWERED_SINCE), TRANSFER_RAIL_REF)
                .limit(limit)
                .fetch(row -> {
                    Currency currency = Currency.of(row.get(TRANSFER_CURRENCY), row.get(TRANSFER_EXPONENT));
                    OffsetDateTime submittedAt = row.get(TRANSFER_SUBMITTED_AT);
                    OffsetDateTime unansweredSince = row.get(TRANSFER_UNANSWERED_SINCE);

                    // Both instants are optional and exactly one of them describes a transfer with no silence at
                    // all: a rail that is answering "still pending" leaves `unanswered_since` null, which is the case
                    // this half of the report exists for.
                    return new StuckTransfer(
                            row.get(STUCK_TRANSFER_RUN),
                            row.get(RUN_GROUP),
                            row.get(STUCK_TRANSFER_ID),
                            row.get(TRANSFER_RAIL_REF),
                            currency,
                            Money.of(row.get(TRANSFER_AMOUNT), currency),
                            TransferStatus.ofWireName(row.get(STUCK_TRANSFER_STATUS)),
                            row.get(TRANSFER_DETAIL),
                            row.get(TRANSFER_UNANSWERED),
                            submittedAt == null ? null : submittedAt.toInstant(),
                            unansweredSince == null ? null : unansweredSince.toInstant());
                });
    }

    /**
     * Which transfers the report is about: the two ways of being stuck, in one predicate.
     *
     * <p><strong>One expression, used by both queries.</strong> The list and the count have to agree - the report's
     * total is the size of the set the list is drawn from - and two hand-written copies of a two-branch condition
     * would be a place for them to drift apart, in the one pair of numbers an operator decides with.
     *
     * <p>A null comparison is never true in SQL, so a transfer with no silence cannot satisfy the first branch and
     * one no rail ever accepted cannot satisfy the second: the null cases need no extra guard. A resolved transfer is
     * out either way - its silence is cleared by the answer, and the second branch requires it to still be at the
     * rail.
     */
    private static Condition stuck(Instant unansweredCutOff, Instant unconfirmedCutOff) {
        return STUCK_TRANSFER_STATUS.eq(TransferStatus.SUBMITTED.wireName())
                .and(TRANSFER_SUBMITTED_AT.le(unconfirmedCutOff.atOffset(ZoneOffset.UTC)))
                .or(TRANSFER_UNANSWERED_SINCE.le(unansweredCutOff.atOffset(ZoneOffset.UTC)));
    }

    @Override
    public int countStuckTransfers(Instant unansweredCutOff, Instant unconfirmedCutOff) {
        Objects.requireNonNull(unansweredCutOff, "unansweredCutOff");
        Objects.requireNonNull(unconfirmedCutOff, "unconfirmedCutOff");

        // No join: the count is a fact about transfers, and pulling the run in would make this query slower for
        // every row it counted in order to answer a question about none of them.
        return dsl.selectCount()
                .from(TRANSFERS)
                .where(stuck(unansweredCutOff, unconfirmedCutOff))
                .fetchOne(0, Integer.class);
    }

    /** The partition's summary, its positions and its transfers. One partition, one currency, no totals. */
    private void writePartition(SettlementRun run, CurrencyPlan partition) {
        Currency currency = partition.currency();
        NettingResult netting = partition.netting();
        OffsetDateTime now = run.createdAt().atOffset(ZoneOffset.UTC);

        dsl.insertInto(PARTITIONS)
                .columns(PARTITION_RUN, PARTITION_CURRENCY, PARTITION_EXPONENT, PARTITION_GROSS, PARTITION_NET,
                        PARTITION_TAX, PARTITION_TAX_SAVED)
                .values(run.id(), currency.code(), (short) currency.minorUnitExponent(),
                        netting.gross().amountMinor(), netting.net().amountMinor(),
                        netting.tax().amountMinor(), netting.taxSaved().amountMinor())
                .execute();

        for (NetPosition position : netting.positions()) {
            dsl.insertInto(POSITIONS)
                    .columns(POSITION_RUN, POSITION_CURRENCY, DSL.field("currency_exponent", Short.class),
                            POSITION_PARTICIPANT, POSITION_NET)
                    .values(run.id(), currency.code(), (short) currency.minorUnitExponent(),
                            position.userId(), position.net().amountMinor())
                    .execute();
        }

        for (TransferInstruction transfer : netting.transfers()) {
            String railRef = railRefOf(run.id(), currency, transfer);

            dsl.insertInto(TRANSFERS)
                    .columns(TRANSFER_ID, TRANSFER_RUN, TRANSFER_CURRENCY, TRANSFER_EXPONENT, TRANSFER_FROM,
                            TRANSFER_TO, TRANSFER_AMOUNT, TRANSFER_RAIL_REF, TRANSFER_STATUS, TRANSFER_CREATED)
                    .values(transferIdOf(railRef), run.id(), currency.code(),
                            (short) currency.minorUnitExponent(), transfer.fromUserId(), transfer.toUserId(),
                            transfer.amount().amountMinor(), railRef, PENDING, now)
                    .execute();
        }
    }

    /**
     * RULE S2's rail reference: derived from the transfer's own coordinates, so a re-submission is the same
     * reference rather than a second movement.
     *
     * <p>The spec's tuple is {@code (run_id, from, to, amount)} and the currency is added here. Within one run
     * two currencies can produce the same three values - INR 100 and JPY 100 between the same pair - and they
     * are two different movements with two different rail references.
     */
    static String railRefOf(UUID runId, Currency currency, TransferInstruction transfer) {
        return "reductxn/" + runId + "/" + currency.code() + "/" + transfer.fromUserId() + "/"
                + transfer.toUserId() + "/" + transfer.amount().amountMinor();
    }

    /**
     * The transfer's identity, derived from its rail reference rather than drawn at random.
     *
     * <p>The schema's {@code UNIQUE (rail_ref)} is what actually forbids a double movement; deriving the id from
     * the same coordinates means a duplicate insert collides on the primary key too, so the failure is a
     * duplicate rather than two rows that differ only in their ids.
     */
    static UUID transferIdOf(String railRef) {
        return UUID.nameUUIDFromBytes(railRef.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A funding's identity, derived from its run and the caller's reference.
     *
     * <p>Derived for the same reason every other id here is: the unique constraint on the reference is what
     * forbids one arrival being counted twice, and deriving the id from the same pair means a duplicate insert
     * collides on the primary key too - so a race is a duplicate rather than two rows that differ only in their
     * ids.
     */
    static UUID fundingIdOf(UUID runId, String fundingRef) {
        return UUID.nameUUIDFromBytes((runId + ":funding:" + fundingRef).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The run's plan as stored, per currency.
     *
     * <p>Two queries for the whole run plus one for its partitions, and the per-currency gathering happens here
     * rather than in SQL: the transfers and positions are read by run and grouped by currency in a map, which
     * keeps the statement simple and the grouping visible. The alternative is a query per currency, which is
     * fine for a group with two currencies and not for one with twenty.
     */
    private Optional<ComputedRun> describe(Record cycle) {
        UUID cycleId = cycle.get(CYCLE_ID);

        Record runRow = dsl.select(RUN_ID, RUN_STATUS, RUN_COMPUTED, RUN_CREATED)
                .from(RUNS)
                .where(RUN_CYCLE.eq(cycleId))
                .fetchOne();

        if (runRow == null) {
            // A cycle can be opened before anything computes it - that is what `openCycle` is for, and a
            // settlement policy needs it: the instant the books are closed at is decided first, and the plan is a
            // consequence of it. So an empty answer here is the ordinary state of a cycle somebody has just
            // opened, and it is what `findRun` must report: `ComputeRun` reads it as "no plan yet" and plans; a
            // throw would make every opened cycle an error in the cadence that exists to compute it.
            //
            // The pair of rows being written together is still true of `open`, which writes both, and of the
            // retry path, which finds both. What is no longer true is that the two acts are the same act.
            return Optional.empty();
        }

        UUID runId = runRow.get(RUN_ID);

        List<PlannedTransfer> transfers = new ArrayList<>();
        Map<String, List<PlannedTransfer>> transfersByCurrency = new HashMap<>();
        for (Record row : dsl.select(TRANSFER_ID, TRANSFER_CURRENCY, TRANSFER_EXPONENT, TRANSFER_FROM, TRANSFER_TO,
                        TRANSFER_AMOUNT, TRANSFER_RAIL_REF, TRANSFER_STATUS, TRANSFER_DETAIL, TRANSFER_UNANSWERED,
                        TRANSFER_NEXT_ATTEMPT, TRANSFER_UNANSWERED_SINCE, TRANSFER_SUBMITTED_AT, TRANSFER_RESOLVED_AT,
                        TRANSFER_RESOLVED_BY, TRANSFER_RESOLVED_BASIS)
                .from(TRANSFERS)
                .where(TRANSFER_RUN.eq(runId))
                .orderBy(TRANSFER_RAIL_REF.asc())
                .fetch()) {

            Currency currency = Currency.of(row.get(TRANSFER_CURRENCY), row.get(TRANSFER_EXPONENT));
            OffsetDateTime nextAttempt = row.get(TRANSFER_NEXT_ATTEMPT);
            OffsetDateTime unansweredSince = row.get(TRANSFER_UNANSWERED_SINCE);
            OffsetDateTime submittedAt = row.get(TRANSFER_SUBMITTED_AT);
            OffsetDateTime resolvedAt = row.get(TRANSFER_RESOLVED_AT);
            PlannedTransfer transfer = new PlannedTransfer(
                    row.get(TRANSFER_ID),
                    currency,
                    row.get(TRANSFER_FROM),
                    row.get(TRANSFER_TO),
                    Money.of(row.get(TRANSFER_AMOUNT), currency),
                    row.get(TRANSFER_RAIL_REF),
                    TransferStatus.ofWireName(row.get(TRANSFER_STATUS)),
                    row.get(TRANSFER_DETAIL),
                    row.get(TRANSFER_UNANSWERED),
                    nextAttempt == null ? null : nextAttempt.toInstant(),
                    unansweredSince == null ? null : unansweredSince.toInstant(),
                    submittedAt == null ? null : submittedAt.toInstant(),
                    resolvedAt == null ? null : resolvedAt.toInstant(),
                    row.get(TRANSFER_RESOLVED_BY),
                    row.get(TRANSFER_RESOLVED_BASIS));

            transfers.add(transfer);
            transfersByCurrency.computeIfAbsent(currency.code(), ignored -> new ArrayList<>()).add(transfer);
        }

        // Every position, not only the clearing ones: quorum is computed over the members the run concerns, which
        // is a question about all of them. Rebuilt through NetPosition so a row that broke an invariant fails
        // here rather than being counted into a quorum.
        List<NetPosition> positions = new ArrayList<>();
        Map<String, Long> clearingByCurrency = new HashMap<>();

        for (Record row : dsl.select(POSITION_CURRENCY, POSITION_EXPONENT, POSITION_PARTICIPANT, POSITION_NET)
                .from(POSITIONS)
                .where(POSITION_RUN.eq(runId))
                .orderBy(POSITION_CURRENCY.asc(), POSITION_PARTICIPANT.asc())
                .fetch()) {

            Currency currency = Currency.of(row.get(POSITION_CURRENCY), row.get(POSITION_EXPONENT));
            long net = row.get(POSITION_NET);

            if (FxClearing.isClearing(row.get(POSITION_PARTICIPANT))) {
                clearingByCurrency.put(currency.code(), net);
            } else {
                positions.add(new NetPosition(row.get(POSITION_PARTICIPANT), Money.of(net, currency)));
            }
        }

        Set<UUID> approvers = new TreeSet<>();
        Set<UUID> rejecters = new TreeSet<>();

        for (Record row : dsl.select(DECISION_MEMBER, DECISION_VALUE)
                .from(DECISIONS)
                .where(DECISION_RUN.eq(runId))
                .orderBy(DECISION_MEMBER.asc())
                .fetch()) {

            UUID member = row.get(DECISION_MEMBER);

            if (RunDecision.Decision.ofWireName(row.get(DECISION_VALUE)) == RunDecision.Decision.APPROVE) {
                approvers.add(member);
            } else {
                rejecters.add(member);
            }
        }

        List<Money> fundings = new ArrayList<>();
        for (Record row : dsl.select(FUNDING_CURRENCY, FUNDING_EXPONENT, FUNDING_AMOUNT)
                .from(FUNDINGS)
                .where(FUNDING_RUN.eq(runId))
                .orderBy(FUNDING_REF.asc())
                .fetch()) {

            Currency currency = Currency.of(row.get(FUNDING_CURRENCY), row.get(FUNDING_EXPONENT));
            fundings.add(Money.of(row.get(FUNDING_AMOUNT), currency));
        }

        List<PartitionOutcome> partitions = new ArrayList<>();
        for (Record row : dsl.select(PARTITION_CURRENCY, PARTITION_EXPONENT, PARTITION_GROSS, PARTITION_NET,
                        PARTITION_TAX, PARTITION_TAX_SAVED)
                .from(PARTITIONS)
                .where(PARTITION_RUN.eq(runId))
                .orderBy(PARTITION_CURRENCY.asc())
                .fetch()) {

            partitions.add(partitionOutcome(row, transfersByCurrency, clearingByCurrency));
        }

        SettlementRun run = new SettlementRun(
                runId,
                cycle.get(CYCLE_GROUP),
                cycleId,
                RunStatus.ofWireName(runRow.get(RUN_STATUS)),
                cycle.get(CYCLE_AS_OF).toInstant(),
                runRow.get(RUN_COMPUTED).toInstant(),
                runRow.get(RUN_CREATED).toInstant());

        // The requirement is taken from the partitions, in report order, and the coverage from the rows. Two
        // readings of the same run give the same Funding, which is what makes "is it funded?" a single answer.
        List<Money> requirements = partitions.stream()
                .filter(PartitionOutcome::requiresExternalFunding)
                .map(PartitionOutcome::externalRequirement)
                .toList();

        return Optional.of(new ComputedRun(run, partitions, transfers, Quorum.of(positions, approvers, rejecters),
                Funding.of(requirements, fundings)));
    }

    /** One partition's summary, with its transfers counted and split, and its clearing position from the rows. */
    private PartitionOutcome partitionOutcome(
            Record partition, Map<String, List<PlannedTransfer>> transfersByCurrency,
            Map<String, Long> clearingByCurrency) {

        Currency currency = Currency.of(partition.get(PARTITION_CURRENCY), partition.get(PARTITION_EXPONENT));
        String code = currency.code();

        List<PlannedTransfer> transfers = transfersByCurrency.getOrDefault(code, List.of());

        int memberCount = 0;
        int clearingCount = 0;
        Money memberTotal = Money.zero(currency);

        for (PlannedTransfer transfer : transfers) {
            boolean touchesClearing = FxClearing.isClearing(transfer.fromUserId())
                    || FxClearing.isClearing(transfer.toUserId());

            if (touchesClearing) {
                clearingCount++;
            } else {
                memberCount++;
                memberTotal = memberTotal.plus(transfer.amount());
            }
        }

        // The clearing position comes from the positions rather than from the transfers, because a currency
        // whose clearing position is non-zero is what RULE S4 gates on - and the clearing account's absence
        // from the plan means zero, which is the ordinary case rather than a missing row.
        Money clearingPosition = Money.of(clearingByCurrency.getOrDefault(code, 0L), currency);

        return new PartitionOutcome(
                currency,
                Money.of(partition.get(PARTITION_GROSS), currency),
                Money.of(partition.get(PARTITION_NET), currency),
                Money.of(partition.get(PARTITION_TAX), currency),
                Money.of(partition.get(PARTITION_TAX_SAVED), currency),
                clearingPosition,
                clearingPosition.absolute(),
                memberTotal,
                memberCount,
                clearingCount);
    }
}

