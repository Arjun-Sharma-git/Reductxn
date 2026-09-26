package io.reductxn.settlement;




import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.Escalation;
import io.reductxn.settlement.port.in.Escalations;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.ReadEscalations;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.SilentTransfer;
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

/**
 * When patience runs out: what the worker says about a rail that has stopped answering (section 4.4's "or
 * escalate", ADR 0024).
 *
 * <p><strong>The subject is a policy, so the tests are about the line it draws.</strong> The retry machinery was
 * already here and already complete - a silent rail is asked again with a growing backoff, for ever - and what no
 * part of the product did was decide that "for ever" had stopped being patience. The assertions below are about
 * that decision and about how it is told: a silence seconds old is nobody's problem, a silence hours old is
 * reported with the rail's own words, the duration is measured from the first unanswered call rather than the
 * last, and an answer ends it.
 *
 * <p><strong>And about what escalation is not.</strong> The worker cannot decide a silent transfer's fate: it does
 * not know whether the money moved, so nothing here writes {@code failed} or {@code confirmed} on the strength of
 * silence. Two of these tests pin that - one shows the transfer still where it was after being escalated, and one
 * shows that a run which failed is not quietly resumed by a later pass (a hole this slice found: the gates have
 * nothing to say about a run that is already over).
 *
 * <p>The clock is the movable one, because a two-hour policy is not testable any other way.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class,
        MovingClockConfiguration.class})
class EscalationTest extends WorkerDatabaseTest {

    @Autowired
    private StubRail rail;

    @Autowired
    private DriveRuns driveRuns;

    @Autowired
    private ReadEscalations readEscalations;

    @Autowired
    private Escalation escalation;

    @Autowired
    private Runs runs;

    @Autowired
    private MovingClockConfiguration.MovingClock clock;

    @BeforeEach
    void resetTheRail() {
        rail.reset();
    }

    /** The escalation report as of now, using the policy's own cut-off. */
    private Escalations escalations() {
        return readEscalations.silentLongerThan(escalation.cutOff(clock.instant()), 20);
    }

    /** A run whose rail will not answer anything, approved and funded so that execution is the only question. */
    private ComputedRun aRunTheRailWillNotAnswer() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        for (PlannedTransfer transfer : run.transfers()) {
            rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);
        }

        return run;
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

        assertThat(escalations().total())
                .as("the rail has been quiet for a moment and the policy allows hours: escalating that would make "
                        + "every blip an incident")
                .isZero();

        // The worker has, however, noticed: the silence is a row, so a duration has somewhere to start even while
        // the answer is "not yet".
        assertThat(transfersOf(run).get(0).unansweredSince()).isEqualTo(startedSilent);
    }

    @Test
    void aSilencePastPatienceIsReportedWithTheRailsOwnWords() {
        ComputedRun run = aRunTheRailWillNotAnswer();

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        Escalations escalations = escalations();

        assertThat(escalations.total()).isEqualTo(run.transfers().size());
        assertThat(escalations.transfers()).hasSize(run.transfers().size());
        assertThat(escalations.truncated()).isFalse();
        assertThat(escalations.cutOff())
                .as("the report carries the line the policy drew, so a reader can say what 'stuck' meant")
                .isEqualTo(escalation.cutOff(clock.instant()));

        for (SilentTransfer silent : escalations.transfers()) {
            assertThat(silent.status())
                    .as("the rail never took the instruction, so nothing has moved and nothing is unknown - a report "
                            + "that said only 'unanswered' would hide that from the person reading it")
                    .isEqualTo(TransferStatus.AUTHORIZED);
            assertThat(silent.unansweredAttempts()).isEqualTo(1);
            assertThat(silent.silentFor(clock.instant())).isEqualTo(Duration.ofHours(3));
            assertThat(silent.runId()).isEqualTo(run.run().id());
            assertThat(silent.groupId()).isEqualTo(GROUP);
            assertThat(silent.amount().amountMinor()).isPositive();
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
    }

    @Test
    void anAnswerEndsTheSilenceAndTakesItOutOfTheReport() {
        ComputedRun run = aRunTheRailWillNotAnswer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));
        assertThat(escalations().total()).isPositive();

        // The rail comes back, which is the ordinary end of an outage: every one of its calls is answered now.
        for (PlannedTransfer each : run.transfers()) {
            rail.scripting(each.railRef(), StubRail.Behaviour.CONFIRM);
        }

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(escalations().total())
                .as("a rail that has answered is a working rail, so nothing here is stuck or waiting any more")
                .isZero();
        assertThat(transferOf(run, transfer.id()).unansweredSince()).isNull();
        assertThat(transferOf(run, transfer.id()).unansweredAttempts()).isZero();
        assertThat(storedRunStatus(run.run().id()))
                .as("and the outage is over rather than merely quiet: the transfers the rail finally answered were "
                        + "booked, so the run is done")
                .isEqualTo("done");
    }

    @Test
    void aSilentRailIsNeverDeclaredToHaveDecided() {
        ComputedRun run = aRunTheRailWillNotAnswer();

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));
        ok(driveRuns.driveOne(run.run().id()));

        assertThat(escalations().total()).isPositive();
        assertThat(transferOf(run, run.transfers().get(0).id()).status())
                .as("nothing has been decided and nothing is known: marking the transfer failed would assert that "
                        + "the money did not move, and marking it confirmed would assert that it did")
                .isEqualTo(TransferStatus.AUTHORIZED);
        assertThat(storedRunStatus(run.run().id()))
                .as("and the run is not failed either - escalation is a report, not a fate")
                .isEqualTo("executing");
    }

    @Test
    void aFailedRunIsNotResumedByALaterPass() {
        // One transfer the rail refuses outright, which fails the run (S3), and one it never answers, which stays in
        // flight. Before this slice a manual pass would offer the in-flight one again: the run is over, nothing said
        // so, and the gates have no opinion about a run that has already finished.
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

        // Which is exactly why escalation matters: this transfer is no longer anybody's automatic problem, so it has
        // to be somebody's visible one.
        clock.advance(Duration.ofHours(3));

        assertThat(escalations().transfers())
                .as("the report reads transfers rather than runs, so a silence inside a run that is over is still "
                        + "found - and this is the worst case for a person to have to guess about")
                .anyMatch(silentTransfer -> silentTransfer.transferId().equals(silent.id()));
    }
}
