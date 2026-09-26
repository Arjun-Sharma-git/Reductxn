package io.reductxn.settlement;

import io.reductxn.settlement.port.in.ComputedCycles;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pass that computes what somebody opened: the reply to a cycle, on a cadence.
 *
 * <p><strong>What it is defending is that a request in the database gets an answer without a person.</strong> A
 * cycle opened by an operator - and later by a group's schedule, and later by a member through the API - used to be
 * unreachable: the only thing that computed a plan was the same call that opened the cycle, so a cycle that was
 * merely opened would have waited for ever while the worker looked healthy. Every test here starts from a cycle no
 * run covers, which is a state that could not be created before this slice.
 *
 * <p>The order and the bounding are asserted here rather than in a unit test because they are the SQL: the pass's
 * own decisions are covered by {@code ComputeOpenedCyclesServiceTest}, and the ordering has to be shown against
 * Postgres's {@code timestamptz} comparisons - which is the reason these tests use a real one.
 */
@SpringBootTest(classes = SettlementWorkerApplication.class)
class ComputeOpenedCyclesTest extends WorkerDatabaseTest {

    /**
     * A group outside the shared fixtures: no members, and no commitments unless a test gives it one.
     *
     * <p>It exists because a pass over cycles has to be a pass over *groups*: two cycles in one group cannot both
     * be computed, since the first run settles the commitments they share and the second would be refused.
     */
    private static final UUID QUIET_GROUP = UUID.fromString("77777777-7777-4777-8777-777777777777");

    /**
     * When the fixtures' cycles were opened. Fixed, and in the past, because the ordering under test is by that
     * column - and never mixed with a cycle the use case opened, whose instant comes from the worker's clock.
     */
    private static final Instant OPENED_AT = Instant.parse("2026-01-15T12:00:00Z");

    private static final int BATCH = 10;

    @Test
    void aPassComputesTheCyclesSomebodyOpened() {
        obligation(ALICE, BORIS, 5_000, "INR", 2);

        openCycle.open(new RunRequest(GROUP, AS_OF));

        ComputedCycles report = computeOpenedCycles.computeOpened(BATCH);

        assertThat(report.outcomes()).hasSize(1);

        ComputedCycles.CycleOutcome outcome = report.outcomes().get(0);

        assertThat(outcome.computed()).isTrue();
        assertThat(outcome.status())
                .as("a computed run starts where the group decides, not where the worker does")
                .isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(outcome.groupId()).isEqualTo(GROUP);
        assertThat(outcome.asOf()).isEqualTo(AS_OF);
        assertThat(countIn("settlement_runs")).isEqualTo(1);
    }

    @Test
    void aCycleThatAlreadyHasARunIsNotOfferedAgain() {
        obligation(ALICE, BORIS, 5_000, "INR", 2);

        // Computed the way an operator's `compute` does it: the cycle and the plan in one act. There is no cycle
        // without a run left behind, which is what makes the next assertion a test of the query rather than of the
        // fixture.
        ComputedRun computed = computed(new RunRequest(GROUP, AS_OF));

        assertThat(computeOpenedCycles.computeOpened(BATCH).outcomes())
                .as("a cycle with a plan is not a request for another one: `one_run_per_cycle` is a constraint, and "
                        + "a second plan would be a second answer to a question the group has been asked")
                .isEmpty();
        assertThat(countIn("settlement_runs")).isEqualTo(1);
        assertThat(computed.run().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void aCycleWithNothingToSettleIsRefusedAndReportedAndLookedAtAgain() {
        openCycle.open(new RunRequest(QUIET_GROUP, AS_OF));

        ComputedCycles first = computeOpenedCycles.computeOpened(BATCH);

        assertThat(first.outcomes()).hasSize(1);
        assertThat(first.refused()).isEqualTo(1);
        assertThat(first.outcomes().get(0).refusal())
                .as("the refusal is the use case's own, reported rather than raised: a group that has not filled "
                        + "its cycle yet is an ordinary state and not an alert")
                .contains("settlement.nothing_to_settle");
        assertThat(countIn("settlement_runs")).isZero();

        assertThat(computeOpenedCycles.computeOpened(BATCH).outcomes())
                .as("and it is looked at again on the next pass, because it can become computable: an obligation "
                        + "recorded later can carry an instant inside this cycle, and a pass that dropped it would "
                        + "lose a real settlement. Nothing records that a pass looked at a cycle - that missing "
                        + "column is named in the port")
                .hasSize(1);
    }

    @Test
    void aRefusalDoesNotStopTheRestOfThePass() {
        obligation(ALICE, BORIS, 5_000, "INR", 2);

        // The refusable cycle is the older one, so it is attempted first: a pass that stopped on a refusal would
        // report one outcome, and this asserts two.
        cycleOpenedAt(QUIET_GROUP, AS_OF, OPENED_AT.minusSeconds(86_400));
        cycleOpenedAt(GROUP, AS_OF, OPENED_AT);

        ComputedCycles report = computeOpenedCycles.computeOpened(BATCH);

        assertThat(report.outcomes()).hasSize(2);
        assertThat(report.refused()).isEqualTo(1);
        assertThat(report.computed()).isEqualTo(1);
        assertThat(countIn("settlement_runs")).isEqualTo(1);
    }

    @Test
    void theOldestRequestIsServedFirst() {
        obligation(ALICE, BORIS, 5_000, "INR", 2);

        UUID older = cycleOpenedAt(GROUP, AS_OF, OPENED_AT.minusSeconds(86_400));
        UUID newer = cycleOpenedAt(QUIET_GROUP, AS_OF, OPENED_AT);

        ComputedCycles first = computeOpenedCycles.computeOpened(1);

        assertThat(first.outcomes()).hasSize(1);
        assertThat(first.outcomes().get(0).cycleId())
                .as("a queue of requests is served in the order it arrived. Taking the newest first would leave the "
                        + "oldest request - the member who has been waiting longest - for last")
                .isEqualTo(older);

        ComputedCycles second = computeOpenedCycles.computeOpened(1);

        assertThat(second.outcomes().get(0).cycleId())
                .as("and the pass after that takes what is left, so a backlog drains in arrival order")
                .isEqualTo(newer);
        assertThat(second.refused()).isEqualTo(1);
    }

    @Test
    void theBatchBoundsOnePass() {
        obligation(ALICE, BORIS, 5_000, "INR", 2);
        obligationFor(QUIET_GROUP, 7_500);

        cycleOpenedAt(GROUP, AS_OF, OPENED_AT.minusSeconds(86_400));
        cycleOpenedAt(QUIET_GROUP, AS_OF, OPENED_AT);

        assertThat(computeOpenedCycles.computeOpened(1).outcomes())
                .as("a pass takes the batch it was given rather than the whole backlog")
                .hasSize(1);
        assertThat(computeOpenedCycles.computeOpened(1).outcomes())
                .as("and the pass after that takes what is left, so a backlog drains one pass at a time - which "
                        + "is what makes `compute-opened` usable from a cron entry")
                .hasSize(1);
        assertThat(computeOpenedCycles.computeOpened(1).outcomes())
                .as("with nothing left once both have plans")
                .isEmpty();
    }

    /**
     * A commitment in a group of this test's choosing.
     *
     * <p>The shared fixture writes to the group the rest of the suite uses, and the rows written here are the rows
     * the API writes - a test standing in for a second group's recorded commitments rather than a second way of
     * recording them.
     */
    private void obligationFor(UUID groupId, long amountMinor) {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor, currency_code,
                                         currency_exponent, memo, idempotency_key, quote_id, incurred_at, voided_at)
                VALUES (?, ?, ?, ?, ?, 'INR', 2, NULL, ?, NULL, TIMESTAMPTZ '2026-09-20 10:00:00+00', NULL)
                """, id, groupId, ALICE, BORIS, amountMinor, "key-" + id);
    }

    @Test
    void aBatchOfZeroIsRefusedBeforeQueryingAnything() {
        assertThatThrownBy(() -> computeOpenedCycles.computeOpened(0))
                .as("a computer told to consider nothing would look like a worker that is broken rather than one "
                        + "that is idle, which is the same argument the driver makes for its own batch")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one cycle");
    }

    /**
     * A cycle written at an instant this test chooses, because what is under test is the order cycles were opened
     * in.
     *
     * <p><strong>This is the API's statement, written here on purpose.</strong> {@code apps/api} opens cycles with
     * these four columns - {@code POST /v1/groups/{groupId}/cycles}, through {@code JooqCycles} - because the
     * member's surface is there and the run is here, with no dependency between the two deployables (ADR 0033, ADR
     * 0020). A test in this module may not call it, so it writes the same SQL instead, which is what pins the
     * crossing: {@code OpenCycleApiTest} asserts the row it wrote, this asserts that the cadence computes it, and a
     * change to the table fails one of them rather than a deployment.
     *
     * <p>{@code open} uses the worker's clock, and two cycles opened a millisecond apart would make this a coin
     * toss on the tie-break rather than a test of the ordering. The id is random where {@code ComputeRunService}
     * proposes a derived one, and that is not a shortcut: the id is a proposal the database may reject in favour of
     * the row its pair already has, which is why the API can mint its own and why the pass reports the id it read.
     */
    private UUID cycleOpenedAt(UUID groupId, Instant asOf, Instant openedAt) {
        UUID id = UUID.randomUUID();

        jdbc.update("INSERT INTO settlement_cycles (id, group_id, as_of, created_at) VALUES (?, ?, ?, ?)",
                id, groupId, OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC));

        return id;
    }
}
