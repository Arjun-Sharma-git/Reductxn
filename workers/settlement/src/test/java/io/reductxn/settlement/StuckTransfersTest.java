package io.reductxn.settlement;

import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.Escalation;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.ReadStuckTransfers;
import io.reductxn.settlement.port.in.Reconciliation;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.StuckTransfer;
import io.reductxn.settlement.port.in.StuckTransfers;
import io.reductxn.settlement.port.in.TransferStatus;
import io.reductxn.settlement.port.out.Runs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What no rail has decided: the report behind section 4.4's "or escalate" (ADR 0024, ADR 0026).
 *
 * <p><strong>The subject is two policies and one listing.</strong> A transfer gets stuck at a rail in two ways - no
 * call is answered, or the rail keeps answering "still pending" and never confirms - and each is measured against
 * its own number ({@code Escalation}, {@code Reconciliation}) while both appear in the one report a person reads.
 * The assertions below are about those lines and about how they are told: a silence seconds old is nobody's
 * problem, an hour of a rail answering "still pending" is, the age is measured from where the trouble began rather
 * than from the most recent call, the reasons are distinguished, and the oldest is listed first.
 *
 * <p><strong>And about what the report is not.</strong> The worker cannot decide a stuck transfer's fate: it does
 * not know whether the money moved, so nothing here writes {@code failed} or {@code confirmed} on the strength of
 * silence, and nothing is written about having reported it either. One test per half pins that, and another shows
 * that a run which failed is not quietly resumed by a later pass.
 *
 * <p>The clock is the movable one, because a two-hour and a one-hour policy are not testable any other way.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class,
        MovingClockConfiguration.class})
class StuckTransfersTest extends WorkerDatabaseTest {

    @Autowired
    private StubRail rail;

    @Autowired
    private DriveRuns driveRuns;

    @Autowired
    private ReadStuckTransfers readStuckTransfers;

    @Autowired
    private Escalation escalation;

    @Autowired
    private Reconciliation reconciliation;

    @Autowired
    private Runs runs;

    @Autowired
    private MovingClockConfiguration.MovingClock clock;

    @BeforeEach
    void resetTheRail() {
        rail.reset();
    }

    /** The report as of now, using both policies' own cut-offs: what an operator would be looking at. */
    private StuckTransfers stuck() {
        Instant now = clock.instant();

        return readStuckTransfers.stuckLongerThan(escalation.cutOff(now), reconciliation.cutOff(now), 20);
    }

    /** A run with one transfer, approved and funded, whose rail does the given thing. */
    private ComputedRun aRunWithOneTransfer(StubRail.Behaviour behaviour) {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        for (PlannedTransfer transfer : run.transfers()) {
            rail.scripting(transfer.railRef(), behaviour);
        }

        return run;
    }

    /** A run whose rail will not answer anything, approved and funded so that execution is the only question. */
    private ComputedRun aRunTheRailWillNotAnswer() {
        return aRunWithOneTransfer(StubRail.Behaviour.UNREACHABLE);
    }

    /** The transfers as they stand now, read back from the rows rather than remembered. */
    private List<PlannedTransfer> transfersOf(ComputedRun run) {
        return runs.findRunById(run.run().id()).orElseThrow().transfers();
    }

    /** One transfer as it stands now. */
    private PlannedTransfer transferOf(ComputedRun run, UUID transferId) {
        return transfersOf(run).stream()
                .filter(transfer -> transfer.id().equals(transferId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("transfer " + transferId + " is missing from the run"));
    }

    /** How many times the rail was asked to move this transfer. */
    private int attemptsOn(PlannedTransfer transfer) {
        return rail.submissionsOf(transfer.railRef());
    }

    @Test
    void aSilenceSecondsOldIsNotYetAnybodysProblem() {
        ComputedRun run = aRunTheRailWillNotAnswer();
        Instant startedSilent = clock.instant();

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(stuck().total())
                .as("the rail has been quiet for a moment and the policy allows hours: reporting that would make "
                        + "every blip an incident")
                .isZero();

        // The worker has, however, noticed: the silence is a row, so an age has somewhere to start even while the
        // answer is "not yet".
        assertThat(transfersOf(run).get(0).unansweredSince()).isEqualTo(startedSilent);
    }

    @Test
    void aSilencePastPatienceIsReportedWithTheRailsOwnWords() {
        ComputedRun run = aRunTheRailWillNotAnswer();

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        StuckTransfers report = stuck();

        assertThat(report.total()).isEqualTo(run.transfers().size());
        assertThat(report.truncated()).isFalse();
        assertThat(report.unansweredCutOff())
                .as("the report carries the line the policy drew, so a reader can say what 'stuck' meant")
                .isEqualTo(escalation.cutOff(clock.instant()));

        for (StuckTransfer transfer : report.transfers()) {
            assertThat(transfer.status())
                    .as("the rail never took the instruction, so nothing has moved and nothing is unknown")
                    .isEqualTo(TransferStatus.AUTHORIZED);
            assertThat(transfer.reason())
                    .as("no call was answered, which is the half of the report that always existed")
                    .isEqualTo(StuckTransfer.Reason.NO_ANSWER);
            assertThat(transfer.unansweredAttempts()).isEqualTo(1);
            assertThat(transfer.submittedAt()).isNull();
            assertThat(transfer.stuckFor(clock.instant())).isEqualTo(Duration.ofHours(3));
            assertThat(transfer.runId()).isEqualTo(run.run().id());
            assertThat(transfer.groupId()).isEqualTo(GROUP);
            assertThat(transfer.amount().amountMinor()).isPositive();
        }
    }

    @Test
    void theDurationIsFromTheFirstCallOfTheSpellNotTheLast() {
        ComputedRun run = aRunTheRailWillNotAnswer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        Instant firstSilence = clock.instant();

        // Past the backoff, so a second call is made and answered with silence again. This is the case that makes a
        // stored start necessary: a duration measured from the latest attempt would restart at every retry, and a
        // rail that had been down all night would never look like it.
        clock.advance(Duration.ofMinutes(5));
        ok(driveRuns.driveOne(run.run().id()));

        PlannedTransfer after = transferOf(run, transfer.id());

        assertThat(after.unansweredAttempts()).isEqualTo(2);
        assertThat(after.unansweredSince()).isEqualTo(firstSilence);
        assertThat(attemptsOn(transfer)).isEqualTo(2);

        clock.advance(Duration.ofHours(2));

        assertThat(stuck().transfers().get(0).stuckSince())
                .as("and the report measures from that start, so the second call did not restart the clock")
                .isEqualTo(firstSilence);
    }

    @Test
    void anAnswerEndsTheSilenceAndTakesItOutOfTheReport() {
        ComputedRun run = aRunTheRailWillNotAnswer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));
        assertThat(stuck().total()).isPositive();

        // The rail comes back, which is the ordinary end of an outage: every one of its calls is answered now.
        for (PlannedTransfer each : run.transfers()) {
            rail.scripting(each.railRef(), StubRail.Behaviour.CONFIRM);
        }

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(stuck().total())
                .as("a rail that has answered is a working rail, so nothing here is stuck or waiting any more")
                .isZero();
        assertThat(transferOf(run, transfer.id()).unansweredSince()).isNull();
        assertThat(transferOf(run, transfer.id()).submittedAt()).isNull();
        assertThat(transferOf(run, transfer.id()).unansweredAttempts()).isZero();
        assertThat(storedRunStatus(run.run().id()))
                .as("and the outage is over rather than merely quiet: the transfers the rail finally answered were "
                        + "booked, so the run is done")
                .isEqualTo("done");
    }

    @Test
    void aStuckRailIsNeverDeclaredToHaveDecided() {
        ComputedRun run = aRunTheRailWillNotAnswer();

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));
        ok(driveRuns.driveOne(run.run().id()));

        assertThat(stuck().total()).isPositive();
        assertThat(transferOf(run, run.transfers().get(0).id()).status())
                .as("nothing has been decided and nothing is known: marking the transfer failed would assert that "
                        + "the money did not move, and marking it confirmed would assert that it did")
                .isEqualTo(TransferStatus.AUTHORIZED);
        assertThat(storedRunStatus(run.run().id()))
                .as("and the run is not failed either - reporting is not a fate")
                .isEqualTo("executing");
    }

    @Test
    void aRailThatKeepsSayingStillPendingIsReportedToo() {
        // The case this report could not see at all before: the rail answers every call, so no silence begins, the
        // retry counter goes back to zero on every poll, and the movement sits at the rail for as long as the rail
        // likes while every measure the worker had said the transfer was healthy (ADR 0026).
        ComputedRun run = aRunWithOneTransfer(StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        Instant acceptedAt = clock.instant();

        assertThat(stuck().total())
                .as("a rail that has just taken the instruction has not kept anybody waiting yet")
                .isZero();

        clock.advance(Duration.ofHours(1));
        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(1));
        ok(driveRuns.driveOne(run.run().id()));

        StuckTransfers report = stuck();

        assertThat(report.total()).isEqualTo(1);

        StuckTransfer stuck = report.transfers().get(0);

        assertThat(stuck.reason())
                .as("the rail has it, which is the different phone call: no answer is missing, a confirmation is")
                .isEqualTo(StuckTransfer.Reason.NO_CONFIRMATION);
        assertThat(stuck.status()).isEqualTo(TransferStatus.SUBMITTED);
        assertThat(stuck.submittedAt()).isEqualTo(acceptedAt);
        assertThat(stuck.unansweredSince()).as("the rail has been answering all along").isNull();
        assertThat(stuck.unansweredAttempts()).isZero();
        assertThat(stuck.stuckFor(clock.instant()))
                .as("and the age is measured from the acceptance, not from the most recent poll - a measure that "
                        + "restarts whenever the rail speaks is exactly what hid this")
                .isEqualTo(Duration.ofHours(2));
        assertThat(report.unconfirmedCutOff()).isEqualTo(reconciliation.cutOff(clock.instant()));
    }

    @Test
    void aConfirmedTransferKeepsItsAcceptanceAndLeavesTheReport() {
        ComputedRun run = aRunWithOneTransfer(StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        Instant acceptedAt = clock.instant();

        clock.advance(Duration.ofHours(2));
        assertThat(stuck().total()).isEqualTo(1);

        // The rail finally confirms it, which is the ordinary end of a slow confirmation.
        rail.confirm(transfer.railRef());
        ok(driveRuns.driveOne(run.run().id()));

        assertThat(stuck().total())
                .as("an answer ends it, so the report stops naming a transfer that has been decided")
                .isZero();
        assertThat(transferOf(run, transfer.id()).submittedAt())
                .as("and the acceptance is kept: it is when the wait began, not a claim that anything is waiting - "
                        + "which is why the report filters on the state as well as the instant")
                .isEqualTo(acceptedAt);
        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
    }

    @Test
    void aFailedRunIsNotResumedByALaterPass() {
        // One transfer the rail refuses outright, which fails the run (S3), and one it never answers, which stays in
        // flight. A manual pass would offer the in-flight one again if the guard against driving a finished run did
        // not exist - the run is over, nothing said so, and the gates have no opinion about a run that has finished.
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        PlannedTransfer refused = run.transfers().get(0);
        PlannedTransfer silent = run.transfers().get(1);
        rail.scripting(refused.railRef(), StubRail.Behaviour.REJECT);
        rail.scripting(silent.railRef(), StubRail.Behaviour.UNREACHABLE);

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(storedRunStatus(run.run().id())).isEqualTo("failed");
        assertThat(attemptsOn(silent)).isEqualTo(1);

        // Past the backoff, so a pass without the guard would offer the silent transfer again.
        clock.advance(Duration.ofMinutes(5));
        ok(driveRuns.driveOne(run.run().id()));

        assertThat(attemptsOn(silent))
                .as("a run that failed is finished: resuming it would move money for a cycle that has already been "
                        + "reported as not settling")
                .isEqualTo(1);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("failed");

        // Which is exactly why the report matters: this transfer is no longer anybody's automatic problem, so it has
        // to be somebody's visible one.
        clock.advance(Duration.ofHours(3));

        assertThat(stuck().transfers())
                .as("the report reads transfers rather than runs, so a transfer inside a run that is over is still "
                        + "found - and this is the worst case for a person to have to guess about")
                .anyMatch(transfer -> transfer.transferId().equals(silent.id()));
    }

    @Test
    void theOldestTroubleIsListedFirst() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);

        ComputedRun silent = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(silent);
        fundEveryRequirement(silent);
        rail.scripting(silent.transfers().get(0).railRef(), StubRail.Behaviour.UNREACHABLE);
        ok(driveRuns.driveOne(silent.run().id()));
        Instant silenceBegan = clock.instant();

        clock.advance(Duration.ofHours(5));

        // A second cycle for the same group five hours later, whose rail takes the instruction and will not confirm
        // it: two reasons, two ages, one listing.
        ComputedRun atTheRail = computed(new RunRequest(GROUP, AS_OF.plus(Duration.ofHours(6))));
        approveEveryone(atTheRail);
        fundEveryRequirement(atTheRail);
        rail.scripting(atTheRail.transfers().get(0).railRef(), StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);
        ok(driveRuns.driveOne(atTheRail.run().id()));
        Instant acceptedAt = clock.instant();

        clock.advance(Duration.ofHours(1).plusMinutes(10));

        StuckTransfers report = stuck();

        assertThat(report.transfers()).hasSize(2);
        assertThat(report.transfers().stream().map(StuckTransfer::stuckSince).toList())
                .as("ordered by the instant the report measures - the same expression the query orders by, pinned "
                        + "here so an adapter that sorted by something else fails rather than showing a person the "
                        + "wrong thing first")
                .isSorted();
        assertThat(report.transfers().get(0).stuckSince())
                .as("the silence began first, so it is the first thing on the screen")
                .isEqualTo(silenceBegan);
        assertThat(report.transfers().get(1).stuckSince()).isEqualTo(acceptedAt);
    }

    @Test
    void aTransferIsInTheReportWhenItCrossesEitherLine() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        obligation(CAROL, BORIS, 4_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        assertThat(run.transfers()).hasSizeGreaterThan(1);

        rail.scripting(run.transfers().get(0).railRef(), StubRail.Behaviour.UNREACHABLE);

        for (int index = 1; index < run.transfers().size(); index++) {
            rail.scripting(run.transfers().get(index).railRef(), StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);
        }

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(2));

        Instant now = clock.instant();

        // Only the silence is past its line: the reconciliation threshold is drawn a day back, so the transfers the
        // rail took must not appear. The two policies are two decisions, and neither implies the other.
        assertThat(readStuckTransfers.stuckLongerThan(
                now.minus(Duration.ofHours(1)), now.minus(Duration.ofDays(1)), 20).transfers())
                .extracting(StuckTransfer::reason)
                .containsOnly(StuckTransfer.Reason.NO_ANSWER);

        // And with both lines where they count, the same report carries both reasons - which is the point of one
        // listing: whoever reads it does not have to know which kind of stuck to go looking for.
        StuckTransfers both = readStuckTransfers.stuckLongerThan(
                now.minus(Duration.ofHours(1)), now.minus(Duration.ofHours(1)), 20);

        assertThat(both.total()).isEqualTo(run.transfers().size());
        assertThat(both.transfers()).extracting(StuckTransfer::reason)
                .containsExactlyInAnyOrderElementsOf(
                        List.of(StuckTransfer.Reason.NO_ANSWER, StuckTransfer.Reason.NO_CONFIRMATION));
    }

    @Test
    void aStuckTransferCannotBeBuiltFromARowThatCannotBeStuck() {
        UUID runId = new UUID(0L, 11L);
        UUID transferId = new UUID(0L, 12L);

        assertThatThrownBy(() -> new StuckTransfer(runId, GROUP, transferId, "rail-ref-1", INR, Money.of(100L, INR),
                TransferStatus.PENDING, null, 0, null, null))
                .as("a transfer nothing was ever offered has nothing to be stuck about")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no rail has decided");

        assertThatThrownBy(() -> new StuckTransfer(runId, GROUP, transferId, "rail-ref-1", INR, Money.of(100L, INR),
                TransferStatus.SUBMITTED, null, 0, null, null))
                .as("and one with no instant cannot say how long anybody has had to look at it, which is the whole "
                        + "use of the report")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("since when");

        assertThatThrownBy(() -> new StuckTransfer(runId, GROUP, transferId, "rail-ref-1", INR, Money.of(100L, INR),
                TransferStatus.AUTHORIZED, null, 0, clock.instant(), null))
                .as("a transfer the rail never took cannot carry an acceptance, and the only thing that makes such a "
                        + "transfer stuck is a silence")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never took it");
    }
}
