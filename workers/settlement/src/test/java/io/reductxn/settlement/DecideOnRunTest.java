package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quorum over a real database (RULE S1).
 *
 * <p>The arithmetic is {@code QuorumTest}'s. What these tests are about is the other half: that the members a
 * quorum is counted over are the ones the run actually concerns, that an answer from anyone else is refused
 * rather than counted, and that the run's stored status says what happened.
 *
 * <p>The ports are driven rather than the services, as everywhere in this module: a test that called
 * {@code DecideOnRunService} directly would stop being evidence about what a caller can do.
 */
@SpringBootTest(classes = SettlementWorkerApplication.class)
class DecideOnRunTest extends WorkerDatabaseTest {

    @Test
    void quorumNeedsAMajorityOfTheRunsMembers() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        assertThat(run.quorum().members())
                .as("the people the run's money moves, taken from its own plan")
                .containsExactlyInAnyOrder(ALICE, BORIS);
        assertThat(run.quorum().required()).isEqualTo(2);
        assertThat(run.quorum().debtors()).containsExactly(ALICE);
        assertThat(run.quorum().met()).isFalse();

        ComputedRun afterOneApproval = decided(runId, ALICE, RunDecision.Decision.APPROVE);

        assertThat(afterOneApproval.quorum().met())
                .as("one of two is not a majority, even when it is the only debtor who agreed")
                .isFalse();
        assertThat(afterOneApproval.run().status())
                .as("an approval counts and moves nothing: the run enters executing when execution starts, so a "
                        + "group that has approved everything is still looking at a run that has moved nothing")
                .isEqualTo(RunStatus.AWAITING_APPROVAL);

        ComputedRun afterBoth = decided(runId, BORIS, RunDecision.Decision.APPROVE);

        assertThat(afterBoth.quorum().met()).isTrue();
        assertThat(afterBoth.run().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(countIn("settlement_decisions")).isEqualTo(2);
    }

    @Test
    void everyDebtorMustConfirmPersonally() {
        obligation(ALICE, CAROL, 10_000L, "INR", 2);
        obligation(BORIS, CAROL, 6_000L, "INR", 2);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        assertThat(run.quorum().members()).containsExactlyInAnyOrder(ALICE, BORIS, CAROL);
        assertThat(run.quorum().debtors()).containsExactlyInAnyOrder(ALICE, BORIS);

        decided(runId, CAROL, RunDecision.Decision.APPROVE);
        ComputedRun afterTheCreditorApproved = decided(runId, ALICE, RunDecision.Decision.APPROVE);

        assertThat(afterTheCreditorApproved.quorum().met())
                .as("two approvals of three is a majority and BORIS is paying, so the group has not agreed to "
                        + "move their money")
                .isFalse();
        assertThat(afterTheCreditorApproved.quorum().outstandingDebtors()).containsExactly(BORIS);

        assertThat(decided(runId, BORIS, RunDecision.Decision.APPROVE).quorum().met()).isTrue();
    }

    @Test
    void someoneTheRunDoesNotConcernCannotApprove() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        Result<ComputedRun> refused =
                decideOnRun.decide(new RunDecision(run.run().id(), CAROL, RunDecision.Decision.APPROVE));

        assertThat(refusalCode(refused)).isEqualTo("settlement.not_a_participant");
        assertThat(countIn("settlement_decisions"))
                .as("refused rather than recorded and ignored: an approval that does not count must not look "
                        + "like one that does")
                .isZero();
    }

    @Test
    void theClearingAccountCannotApprove() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        Result<ComputedRun> refused = decideOnRun.decide(
                new RunDecision(run.run().id(), FxClearing.ACCOUNT_ID, RunDecision.Decision.APPROVE));

        assertThat(refusalCode(refused))
                .as("the clearing account is a participant in every cross-currency run and is not a person - the "
                        + "sharpest version of a stranger approving, in the runs with the largest amounts")
                .isEqualTo("settlement.not_a_participant");
        assertThat(run.quorum().members()).doesNotContain(FxClearing.ACCOUNT_ID);
    }

    @Test
    void aMemberCannotAnswerTwice() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        decided(runId, ALICE, RunDecision.Decision.APPROVE);

        assertThat(refusalCode(decideOnRun.decide(new RunDecision(runId, ALICE, RunDecision.Decision.APPROVE))))
                .as("a decision is final, and the primary key behind this is what makes that true for two "
                        + "callers at once rather than only for one")
                .isEqualTo("settlement.already_decided");
        assertThat(countIn("settlement_decisions")).isEqualTo(1);
    }

    @Test
    void rejectingEndsTheRunAndIsRecordedAsAReason() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        ComputedRun rejected = decided(runId, BORIS, RunDecision.Decision.REJECT);

        assertThat(rejected.run().status()).isEqualTo(RunStatus.REJECTED);
        assertThat(rejected.quorum().rejected()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT decision FROM settlement_decisions WHERE run_id = ? AND member_id = ?",
                String.class, runId, BORIS))
                .as("who refused is stored, because a rejected run with no reason is the one thing a group "
                        + "cannot reconstruct")
                .isEqualTo("reject");

        assertThat(refusalCode(decideOnRun.decide(new RunDecision(runId, ALICE, RunDecision.Decision.APPROVE))))
                .as("the run is no longer waiting on the group, so a later approval is refused with the state it "
                        + "is actually in")
                .isEqualTo("settlement.not_waiting_on_the_group");
    }

    @Test
    void aDecisionOnARunThatHasMovedOnIsRefused() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        UUID runId = run.run().id();

        // Written directly, because the point is what happens to a decision on a run already past the waiting
        // states - and reaching that state through the product is ExecuteRunTest's subject, not this one's.
        jdbc.update("UPDATE settlement_runs SET status = 'executing' WHERE id = ?", runId);

        assertThat(refusalCode(decideOnRun.decide(new RunDecision(runId, ALICE, RunDecision.Decision.APPROVE))))
                .isEqualTo("settlement.not_waiting_on_the_group");
    }

    @Test
    void anUnknownRunIsRefusedRatherThanAnsweredWithAnEmptyReport() {
        assertThat(refusalCode(decideOnRun.decide(
                new RunDecision(UUID.randomUUID(), ALICE, RunDecision.Decision.APPROVE))))
                .isEqualTo("settlement.run_not_found");
    }
}
