package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.DrivenRuns;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The driver: what moves a run that nothing else moves.
 *
 * <p>Before this, every test in the module called {@code ExecuteRun} itself. That made the module's tests pass
 * and left a deployment where a run could sit in {@code awaiting_approval} for ever - because the last step had
 * no caller. So the assertions here are about the caller: a run is driven by the driver alone, a run that cannot
 * progress is left where it is with the gate's reason, a run starts as soon as its last fact arrives, and a
 * transfer the rail never answered is finished by a later pass.
 *
 * <p>The cut-offs are explicit rather than implicit. A pass is told how stale an attempt must be
 * ({@link DriveRuns#driveDue}), so a test can express "a pass now" and "a pass a minute later" without waiting
 * for a clock - and that argument is also what makes the threshold a setting rather than a rule about settlement.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class})
class DriveRunsTest extends WorkerDatabaseTest {

    @Autowired
    private StubRail rail;

    @Autowired
    private DriveRuns driveRuns;

    @Autowired
    private Clock clock;

    @BeforeEach
    void resetTheRail() {
        rail.reset();
    }

    @Test
    void aRunNothingIsWaitingOnIsDrivenToDoneByTheDriverAlone() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        DrivenRuns report = ok(driveRuns.driveDue(aPassNow(), 10));

        assertThat(report.outcomes()).hasSize(1);
        assertThat(report.driven()).as("the everybody-has-answered case").isEqualTo(1);
        assertThat(report.completed()).hasSize(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
        assertThat(rail.submissions())
                .as("and the money moved, because the driver drove it rather than the test")
                .hasSize(2);
    }

    @Test
    void aRunThatCannotProgressIsLeftWhereItIsWithTheGatesReason() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        DrivenRuns report = ok(driveRuns.driveDue(aPassNow(), 10));

        assertThat(report.refused()).isEqualTo(1);
        assertThat(report.outcomes().get(0).reason())
                .as("a refusal is an answer, not a fault: the operator reading the report is told which gate is "
                        + "waiting and on whom")
                .contains("settlement.quorum_not_met");
        assertThat(report.outcomes().get(0).status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(rail.submissions())
                .as("and nothing reached a rail, which is the point of the gates being in the use case rather "
                        + "than in the caller")
                .isEmpty();
    }

    @Test
    void aRunStartsAsSoonAsItsLastFactArrives() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        // A pass first, which finds a run waiting on funding and approval and does nothing.
        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).driven()).isZero();
        assertThat(storedRunStatus(run.run().id()))
                .as("a refusal leaves the run exactly where it was - and where that is, is the funding gate's "
                        + "answer rather than the quorum's, because currency the group does not hold blocks a run "
                        + "before a group's approval could allow it (RULE S4)")
                .isEqualTo("awaiting_fx_funding");

        approveEveryone(run);
        fundEveryRequirement(run);

        // The facts have arrived; nothing calls a use case about it. The next pass is what notices.
        DrivenRuns later = ok(driveRuns.driveDue(aPassAMinuteLater(), 10));

        assertThat(later.driven()).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
    }

    @Test
    void aTransferTheRailNeverAnsweredIsResolvedByALaterPass() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        String inFlight = run.transfers().get(0).railRef();
        rail.scripting(inFlight, StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);

        DrivenRuns first = ok(driveRuns.driveDue(aPassNow(), 10));

        assertThat(first.driven()).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id()))
                .as("the rail accepted one transfer and said nothing about it, so the run cannot be done - and "
                        + "must not be failed either: silence is not a refusal")
                .isEqualTo("executing");

        // What a rail does between two passes. No test calls `ExecuteRun` here: the driver is the only caller,
        // and the second pass is what resolves the transfer the first one left in flight - section 4.4's
        // reconciliation claim in the form this project can actually assert.
        rail.confirm(inFlight);

        DrivenRuns second = ok(driveRuns.driveDue(aPassAMinuteLater(), 10));

        assertThat(second.driven()).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
        assertThat(rail.polls()).as("asked rather than re-submitted").isPositive();
        assertThat(rail.submissionsOf(inFlight)).as("offered to the rail once, ever").isEqualTo(1);
    }

    @Test
    void theSameRunIsNotDrivenTwiceWithinTheWindow() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        ok(driveRuns.driveDue(aPassNow(), 10));

        DrivenRuns again = ok(driveRuns.driveDue(aPassNow(), 10));

        assertThat(again.outcomes())
                .as("the window is the anti-stampede guard: a second instance (or a second tick) within it sees "
                        + "nothing due rather than re-driving a run another process is working on")
                .isEmpty();
        assertThat(storedDrivenAt(run.run().id()))
                .as("and the mark the window reads is a real column, written before the attempt")
                .isNotNull();
    }

    @Test
    void aFinishedRunIsNeverConsideredAgain() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        ok(driveRuns.driveDue(aPassNow(), 10));
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");

        DrivenRuns muchLater = ok(driveRuns.driveDue(Instant.parse("2099-01-01T00:00:00Z"), 10));

        assertThat(muchLater.outcomes()).isEmpty();
        assertThat(rail.submissions()).hasSize(1);
    }

    @Test
    void drivingANamedRunIgnoresTheWindow() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        ok(driveRuns.driveDue(aPassNow(), 10));
        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).outcomes()).isEmpty();

        DrivenRuns named = ok(driveRuns.driveOne(run.run().id()));

        assertThat(named.outcomes())
                .as("an operator asking for one run is not a stampede, and refusing on the grounds that it was "
                        + "attempted recently would make the command useless in the case it exists for")
                .hasSize(1);
        assertThat(named.refused()).isEqualTo(1);
    }

    @Test
    void drivingARunThatDoesNotExistIsARefusal() {
        Result<DrivenRuns> missing = driveRuns.driveOne(new UUID(0L, 999L));

        assertThat(refusalCode(missing)).isEqualTo("settlement.run_not_found");
    }

    @Test
    void aPassIsBoundedAndTakesTheLeastRecentlyAttemptedFirst() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);

        // Two cycles over one group, an hour apart: two runs, both drivable, and the obligation is open at both
        // cut-offs - so this is about the queue rather than about what the runs contain.
        computed(new RunRequest(GROUP, AS_OF));
        computed(new RunRequest(GROUP, AS_OF.minusSeconds(3_600)));

        DrivenRuns oneAtATime = ok(driveRuns.driveDue(aPassNow(), 1));

        assertThat(oneAtATime.outcomes()).hasSize(1);

        DrivenRuns theNextPass = ok(driveRuns.driveDue(aPassAMinuteLater(), 1));

        assertThat(theNextPass.outcomes())
                .as("a batch size bounds one pass, and the next pass takes the run the first one left - oldest "
                        + "attempt first, so a backlog of waiting runs cannot starve one that is ready")
                .hasSize(1);
        assertThat(theNextPass.outcomes().get(0).runId())
                .isNotEqualTo(oneAtATime.outcomes().get(0).runId());
    }

    /**
     * A member's request makes a run due inside the window, and the attempt spends it (ADR 0050).
     *
     * <p>The API is what writes these two columns, and this test writes them the same way - with SQL, because the
     * mechanism under test is the *read* side: the due query's third disjunct and the clear in {@code markDriven}.
     * Writing them through a use case that does not exist in this module would be asserting the writer rather than
     * the reader, which is the half this worker owns.
     */
    @Test
    void aRunAMemberAskedForIsDrivenWithoutWaitingOutTheWindow() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        // One pass, refused by a gate - the run is waiting on the group - so the window has now started.
        ok(driveRuns.driveDue(aPassNow(), 10));

        approveEveryone(run);
        fundEveryRequirement(run);

        // Nothing new is recorded, so the window holds: this is the backoff doing its job, and it is the state a
        // member's request has to beat.
        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).driven())
                .as("asked for nothing, and recently attempted")
                .isZero();

        requestedToExecute(run.run().id(), ALICE);

        DrivenRuns asked = ok(driveRuns.driveDue(aPassNow(), 10));

        assertThat(asked.driven()).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).as("the request is what moved the money").isEqualTo("done");
    }

    /**
     * The request is a flag, not a standing instruction: it is spent by the attempt that honours it.
     *
     * <p>The run is left waiting on its group deliberately - nothing is approved - because a run that settled is
     * terminal and a request against it would be honoured by nothing. Refusing is still an *attempt*: the driver marks
     * it, which is what spends the request, so the third pass has nothing left to act on.
     */
    @Test
    void theAttemptSpendsTheRequestSoTheWindowStartsAgain() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).refused()).isEqualTo(1);

        requestedToExecute(run.run().id(), ALICE);

        // The refusal *is* the attempt - the driver marked it - so the request is spent by it and the third pass has
        // nothing left to act on. `refused()` rather than `driven()`: this run never progresses, because nobody has
        // approved it, and what the two passes after the request are being counted for is that they were attempted.
        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).refused())
                .as("honoured inside the window")
                .isEqualTo(1);

        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).outcomes())
                .as("and spent, so the window is back in force")
                .isEmpty();
        assertThat(storedRequestedBy(run.run().id())).as("who asked stays on the record").isEqualTo(ALICE.toString());
    }

    /** A request, as the API writes it: the flag is now, and the attribution is the member who asked. */
    private void requestedToExecute(UUID runId, UUID memberId) {
        jdbc.update("""
                UPDATE settlement_runs
                SET execute_requested_at = ?, execute_requested_by = ?
                WHERE id = ?
                """, clock.instant().plusSeconds(1).atOffset(ZoneOffset.UTC), memberId, runId);
    }

    private String storedRequestedBy(UUID runId) {
        return jdbc.queryForObject("SELECT execute_requested_by::text FROM settlement_runs WHERE id = ?",
                String.class, runId);
    }

    /**
     * A rail's callback is preferred to a poll, and the money's state is still decided in one place (ADR 0051).
     *
     * <p>The assertion that matters is {@code polls()} being zero: the second pass resolved a transfer it would
     * otherwise have had to ask about, so the callback replaced a network call rather than adding a second way to
     * decide a state. The rail's own words land on the transfer's {@code rail_detail}, which is where a person
     * reading the run finds them - the same column a polled answer would have filled.
     */
    @Test
    void aDeliveredAnswerResolvesATransferWithoutAskingTheRail() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        String inFlight = run.transfers().get(0).railRef();
        rail.scripting(inFlight, StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);

        // The first pass offers both transfers and hears nothing about one of them.
        assertThat(ok(driveRuns.driveDue(aPassNow(), 10)).driven()).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("executing");
        assertThat(rail.submissions()).hasSize(2);

        // What the rail would tell us by poll, delivered instead - written the way `apps/api` writes it when a rail
        // calls back, because that is the other half of this mechanism and the row is the whole of the contract
        // between them.
        railToldUs(inFlight, "confirmed", "settled at 14:02, per the rail's own ledger");

        DrivenRuns second = ok(driveRuns.driveDue(aPassAMinuteLater(), 10));

        assertThat(second.driven()).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
        assertThat(rail.polls())
                .as("the answer had already arrived, so no rail call was made to ask for it")
                .isZero();
        assertThat(storedRailDetail(inFlight)).isEqualTo("settled at 14:02, per the rail's own ledger");
    }

    /**
     * A row on its own moves nothing, and that is the safety this mechanism needs.
     *
     * <p>A callback is consulted only for a transfer the rail has <em>been asked about</em> - the same place a poll
     * would happen - so a delivered answer about a reference nobody offered cannot resolve a transfer, complete a run,
     * or book an entry. Without this, anything able to write one row could claim that money had moved.
     */
    @Test
    void aCallbackAboutATransferTheRailNeverSawIsIgnored() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        String neverOffered = run.transfers().get(0).railRef();
        railToldUs(neverOffered, "confirmed", "which the rail never said, because it was never asked");

        DrivenRuns pass = ok(driveRuns.driveDue(aPassNow(), 10));

        assertThat(pass.refused()).as("the run still waits on its group, whatever a row says").isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("awaiting_approval");
        assertThat(rail.submissions()).isEmpty();
    }

    /** A callback as {@code apps/api} writes it: the reference, the rail's state word, and the rail's own words. */
    private void railToldUs(String railRef, String state, String detail) {
        jdbc.update("""
                INSERT INTO settlement_rail_callbacks (rail_ref, provider, state, detail, received_at)
                VALUES (?, 'mock', ?, ?, ?::timestamptz)
                ON CONFLICT (rail_ref) DO UPDATE
                    SET state = excluded.state,
                        detail = excluded.detail,
                        received_at = excluded.received_at
                """, railRef, state, detail, clock.instant().atOffset(ZoneOffset.UTC));
    }

    private String storedRailDetail(String railRef) {
        return jdbc.queryForObject("SELECT rail_detail FROM settlement_transfers WHERE rail_ref = ?",
                String.class, railRef);
    }

    /** A cut-off one second ago: anything never attempted is due, and anything just attempted is not. */
    private Instant aPassNow() {
        return clock.instant().minusSeconds(1);
    }

    /** A cut-off a minute ahead, which is what the next tick would use with a one-minute window. */
    private Instant aPassAMinuteLater() {
        return clock.instant().plusSeconds(60);
    }

    private String storedDrivenAt(UUID runId) {
        return jdbc.queryForObject("SELECT driven_at::text FROM settlement_runs WHERE id = ?", String.class,
                runId);
    }
}
