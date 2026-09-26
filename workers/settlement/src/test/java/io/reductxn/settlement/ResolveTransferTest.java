package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.Escalation;
import io.reductxn.settlement.port.in.Escalations;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.ReadEscalations;
import io.reductxn.settlement.port.in.ResolveTransfer;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.TransferResolution;
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
 * A person's word about a transfer no rail would decide: the way back from the escalation report (RULE S6, ADR
 * 0025).
 *
 * <p><strong>The subject is an assertion about money, so the tests are about what it may and may not do.</strong>
 * The worker says nothing on the strength of silence (ADR 0024) - so somebody who has asked the rail has to be able
 * to say what it turned out to be, and the assertions below are about that write: that it books a movement exactly
 * as a confirmation does, that it ends the silence and takes the transfer out of the report, that a "no" books
 * nothing, that the rail is never asked, and that an answer already stored is not rewritten.
 *
 * <p><strong>Two of these pin the decision rather than the mechanism.</strong> A person may resolve a transfer the
 * rail never acknowledged, because the worker's record of a submission is not the truth about money - and a person
 * may resolve one that is not yet past the escalation policy, because patience is the worker's prompt to a human
 * rather than a permission a human needs. Both are refusals that were considered and not made, so a test that shows
 * them accepted is the evidence for the decision.
 *
 * <p>The clock is the movable one: a silent rail takes hours to become the report's problem, and a resolution's
 * instant is asserted from it.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class,
        MovingClockConfiguration.class})
class ResolveTransferTest extends WorkerDatabaseTest {

    /** An operator, named the way a deployment would name one. Nothing verifies it - that is the point of storing it. */
    private static final String PRIYA = "priya@acme.example";

    /** What she was reading when she decided, which is what makes the claim checkable later. */
    private static final String STATEMENT = "rail statement 2026-09-27/4417";

    @Autowired
    private StubRail rail;

    @Autowired
    private DriveRuns driveRuns;

    @Autowired
    private ResolveTransfer resolveTransfer;

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

    /** The escalation report as of now, using the policy's own cut-off: what an operator would be looking at. */
    private Escalations escalations() {
        return readEscalations.silentLongerThan(escalation.cutOff(clock.instant()), 20);
    }

    /** A run with one transfer, approved and funded, whose rail never answers anything. */
    private ComputedRun aRunWithOneSilentTransfer() {
        return aRunWithOneTransfer(StubRail.Behaviour.UNREACHABLE);
    }

    /** A run with one transfer - the whole plan, so resolving it is what completes the run. */
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

    /** What one person says about one transfer. */
    private Result<ComputedRun> said(ComputedRun run, UUID transferId, TransferResolution.Outcome outcome) {
        return resolveTransfer.resolve(
                new TransferResolution(run.run().id(), transferId, outcome, PRIYA, STATEMENT));
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
    void aPersonWhoFoundOutTheMoneyMovedBooksItAndCompletesTheRun() {
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));
        Instant assertedAt = clock.instant();

        ComputedRun after = ok(said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED));

        PlannedTransfer resolved = transferOf(run, transfer.id());

        assertThat(resolved.status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(resolved.resolvedBy())
                .as("an unattributed claim about money is the one write this module refuses, so the assertion is "
                        + "stored with the person who made it")
                .isEqualTo(PRIYA);
        assertThat(resolved.resolvedBasis()).isEqualTo(STATEMENT);
        assertThat(resolved.resolvedAt()).isEqualTo(assertedAt);
        assertThat(resolved.unansweredSince())
                .as("the silence is over - an answer arrived, from a person rather than from the rail")
                .isNull();
        assertThat(resolved.unansweredAttempts()).isZero();

        assertThat(countIn("journal_entries"))
                .as("a confirmation is a confirmation: the movement is booked whether the rail or a person "
                        + "established it, with the entry derived from the transfer itself")
                .isEqualTo(1);
        assertThat(after.run().status()).isEqualTo(RunStatus.DONE);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
    }

    @Test
    void theRailIsAskedNothingOnAPersonsWord() {
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        int offered = attemptsOn(transfer);
        int polls = rail.polls();

        ok(said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED));

        assertThat(attemptsOn(transfer))
                .as("the operator already has the answer - that is what the report is for - so resolving asks the "
                        + "rail nothing and moves money through nobody")
                .isEqualTo(offered);
        assertThat(rail.polls()).isEqualTo(polls);
    }

    @Test
    void aPersonSayingTheMoneyDidNotMoveBooksNothingAndEndsTheRun() {
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        ComputedRun after = ok(said(run, transfer.id(), TransferResolution.Outcome.FAILED));

        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.FAILED);
        assertThat(countIn("journal_entries"))
                .as("the money did not move, so there is no posting: a booking here would be the ledger recording "
                        + "a movement that nobody made")
                .isZero();
        assertThat(after.run().status())
                .as("and the run did not settle: it ends as the partial failure S3 makes a first-class outcome")
                .isEqualTo(RunStatus.FAILED);
        assertThat(storedRunStatus(run.run().id())).isEqualTo("failed");
    }

    @Test
    void aTransferTheRailAcceptedAndWentQuietOnCanBeResolved() {
        // The rail took the instruction and never confirmed it, so the transfer is `submitted` and no call went
        // unanswered - it is not in the escalation report at all, and the driver polls it for ever (the sweep that
        // would give up on it does not exist yet). A person who has asked the rail may still resolve it.
        ComputedRun run = aRunWithOneTransfer(StubRail.Behaviour.ACCEPT_WITHOUT_CONFIRMING);
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.SUBMITTED);
        assertThat(transferOf(run, transfer.id()).unansweredSince())
                .as("the rail answered this one, so there is no silence to have outlasted anything")
                .isNull();
        assertThat(escalations().total()).isZero();

        ok(said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED));

        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(countIn("journal_entries")).isEqualTo(1);
        assertThat(storedRunStatus(run.run().id()))
                .as("patience is the worker's prompt to a human, not a permission a human needs: an operator who "
                        + "knows the answer does not have to wait for a report to call this stuck")
                .isEqualTo("done");
    }

    @Test
    void aTransferTheRailNeverAcknowledgedMayStillBeResolvedAsMoved() {
        // The instruction was offered and nothing came back, so the worker only knows that it *asked*. The money may
        // have moved anyway, and the person who checked is the one who knows - which is why this is a command rather
        // than a status write in the worker.
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.AUTHORIZED);

        ok(said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED));

        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(countIn("journal_entries")).isEqualTo(1);
    }

    @Test
    void anAnswerThatHasArrivedIsNotRewritten() {
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));
        ok(said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED));

        clock.advance(Duration.ofMinutes(1));
        Result<ComputedRun> again = said(run, transfer.id(), TransferResolution.Outcome.FAILED);

        assertThat(refusalCode(again)).isEqualTo(RunRefusals.TransferAlreadyResolved.CODE);
        assertThat(refusalOf(again).message())
                .as("the case this refusal meets is an operator re-running after a slow response, so it says what is "
                        + "already stored rather than only that something is")
                .contains(PRIYA)
                .contains(STATEMENT);
        assertThat(countIn("journal_entries"))
                .as("and no second movement was booked on the way to the refusal")
                .isEqualTo(1);
        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.CONFIRMED);
    }

    @Test
    void aTransferARailAnsweredIsRefusedTheSameWayAndNamesNoPerson() {
        ComputedRun run = aRunWithOneTransfer(StubRail.Behaviour.CONFIRM);
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(transferOf(run, transfer.id()).status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(transferOf(run, transfer.id()).resolvedBy()).isNull();

        Result<ComputedRun> again = said(run, transfer.id(), TransferResolution.Outcome.FAILED);

        assertThat(refusalCode(again)).isEqualTo(RunRefusals.TransferAlreadyResolved.CODE);
        assertThat(refusalOf(again).message())
                .as("there was no person, so the refusal says a rail answered - which is the honest answer to 'who "
                        + "decided this'")
                .contains("a rail answered it");
    }

    @Test
    void aTransferNoRailWasAskedAboutCannotBeResolved() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        PlannedTransfer transfer = run.transfers().get(0);

        assertThat(transfer.status())
                .as("recorded with the plan and never offered to a rail, because nothing has approved the run")
                .isEqualTo(TransferStatus.PENDING);

        Result<ComputedRun> refused = said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED);

        assertThat(refusalCode(refused)).isEqualTo(RunRefusals.TransferNotOffered.CODE);
        assertThat(countIn("journal_entries"))
                .as("a person cannot have found out what a rail did with an instruction the rail never saw")
                .isZero();
    }

    @Test
    void aRunTheGroupRejectedHasNothingForAResolutionToAnswer() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        // Alice rejects instead of approving: a decision is final (ADR 0022), so this member answers once.
        decided(run.run().id(), ALICE, RunDecision.Decision.REJECT);

        // The refusal is recorded by the use case that acts on it, so a look is what writes the run's fate (ADR 0022).
        driveRuns.driveOne(run.run().id());

        assertThat(storedRunStatus(run.run().id())).isEqualTo("rejected");

        Result<ComputedRun> refused =
                said(run, run.transfers().get(0).id(), TransferResolution.Outcome.CONFIRMED);

        assertThat(refusalCode(refused))
                .as("a rejected run never reached a rail, so its transfers are pending and there is no answer to have "
                        + "found out - the same refusal as an approved run nobody has driven yet, for the same reason")
                .isEqualTo(RunRefusals.TransferNotOffered.CODE);
    }

    @Test
    void bothIdsMustBeReal() {
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        Result<ComputedRun> stranger = said(run, UUID.randomUUID(), TransferResolution.Outcome.CONFIRMED);

        assertThat(refusalCode(stranger))
                .as("a transfer id that is not in the run named is answered rather than applied, which is why the "
                        + "command takes both ids: a typo resolves nothing")
                .isEqualTo(RunRefusals.NoSuchTransfer.CODE);

        Result<ComputedRun> nowhere = resolveTransfer.resolve(new TransferResolution(
                UUID.randomUUID(), transfer.id(), TransferResolution.Outcome.CONFIRMED, PRIYA, STATEMENT));

        assertThat(refusalCode(nowhere)).isEqualTo(RunRefusals.NoSuchRun.CODE);
        assertThat(countIn("journal_entries")).isZero();
    }

    @Test
    void anAssertionWithNoNameOrNoBasisIsNotAResolution() {
        UUID transferId = UUID.randomUUID();

        assertThatThrownBy(() -> new TransferResolution(GROUP, transferId,
                TransferResolution.Outcome.CONFIRMED, "   ", STATEMENT))
                .as("who is asserting it is the subject of the write, so an unattributed resolution cannot be built "
                        + "at all - which is the whole of this project's answer to who may claim that money moved")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names nobody");

        assertThatThrownBy(() -> new TransferResolution(GROUP, transferId,
                TransferResolution.Outcome.CONFIRMED, PRIYA, null))
                .as("nothing checks the basis, so recording what it was is the only way anybody can check it later")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("looked at");
    }

    @Test
    void resolvingOneTransferDoesNotFinishARunWithOthersOutstanding() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        for (PlannedTransfer each : run.transfers()) {
            rail.scripting(each.railRef(), StubRail.Behaviour.UNREACHABLE);
        }

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        List<PlannedTransfer> transfers = run.transfers();
        assertThat(transfers).hasSizeGreaterThan(1);

        for (int index = 0; index < transfers.size(); index++) {
            ok(said(run, transfers.get(index).id(), TransferResolution.Outcome.CONFIRMED));

            if (index < transfers.size() - 1) {
                assertThat(storedRunStatus(run.run().id()))
                        .as("the run's fate is computed from its transfers by the rule execution uses too "
                                + "(RunOutcome), so one answer in a run with others outstanding leaves it where it was")
                        .isEqualTo("executing");
            }
        }

        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
        assertThat(countIn("journal_entries")).isEqualTo(transfers.size());
    }

    @Test
    void aResolvedTransferLeavesTheEscalationReport() {
        ComputedRun run = aRunWithOneSilentTransfer();
        PlannedTransfer transfer = run.transfers().get(0);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        assertThat(escalations().transfers())
                .as("the report is what tells somebody which movement to ask the rail about")
                .anyMatch(silent -> silent.transferId().equals(transfer.id()));

        ok(said(run, transfer.id(), TransferResolution.Outcome.CONFIRMED));

        assertThat(escalations().total())
                .as("an answered transfer is not silent, so the loop closes: the report stops naming something "
                        + "somebody has already decided")
                .isZero();
    }

    @Test
    void aSilentTransferInsideAFailedRunIsStillResolvable() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);
        fundEveryRequirement(run);

        PlannedTransfer refused = run.transfers().get(0);
        PlannedTransfer silent = run.transfers().get(1);
        rail.scripting(refused.railRef(), StubRail.Behaviour.REJECT);
        rail.scripting(silent.railRef(), StubRail.Behaviour.UNREACHABLE);

        ok(driveRuns.driveOne(run.run().id()));
        clock.advance(Duration.ofHours(3));

        assertThat(storedRunStatus(run.run().id())).isEqualTo("failed");

        // The worst case a person has to guess about: the run says the settlement did not happen and the rail may have
        // moved the money anyway (ADR 0024's finding). The answer is worth recording even though the run cannot
        // become un-failed - and the driver will never come back for this transfer, so a person is all it has.
        ComputedRun after = ok(said(run, silent.id(), TransferResolution.Outcome.CONFIRMED));

        assertThat(transferOf(run, silent.id()).status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(countIn("journal_entries"))
                .as("what moved is booked, which is RULE S3's partial failure recorded properly rather than hidden")
                .isEqualTo(1);
        assertThat(after.run().status())
                .as("and the run stays failed: the failure is a fact about money that did not move, and one leg "
                        + "being confirmed does not un-move what the rail refused")
                .isEqualTo(RunStatus.FAILED);
    }
}
