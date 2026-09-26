package io.reductxn.settlement;

import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRequest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A member's answer written from outside this worker, judged by the run's own arithmetic.
 *
 * <p><strong>This class is the reading half of a contract no module spans.</strong> {@code apps/api} records a
 * member's answer with the shape pinned in {@code DecisionApiTest}; this test writes <em>the same row with the
 * same statement</em> and asserts that the run's quorum counts it. Two deployables, one table, no dependency between
 * them (RULE B6) and no broker in the build (ADR 0020) - so the row is the interface, and a change to
 * {@code settlement_decisions} that broke the crossing should fail one of these two tests rather than a deployment.
 *
 * <p><strong>Both words cross it.</strong> An approval is counted by the run's quorum; a rejection ends the run, and
 * the end is written on the worker's next look because the API writes rows and not states (ADR 0020). Reading a
 * rejection through the same intersection as an approval is what keeps the crossing safe in the other direction
 * too: a refusal from somebody the run does not concern is recorded and inert.
 *
 * <p><strong>What makes that arrangement more than expedient is that quorum is a pure function over stored rows
 * (ADR 0016).</strong> Nothing here needs to know who wrote a decision: the arithmetic reads the rows, intersects
 * the answers with the run's own participants, and an answer from somebody the run does not concern counts for
 * nothing. That is why two of these tests are really one property twice - an answer from the API counts exactly like
 * one from this module's own use case, and a mistaken answer cannot make a run execute.
 *
 * <p>The rows arrive as SQL rather than through a helper, deliberately: reproducing the writer's statement is the
 * assertion. A helper shared with the API is not available (the modules may not depend on each other) and would in
 * any case hide the thing being pinned.
 */
@SpringBootTest
class RecordedDecisionsTest extends WorkerDatabaseTest {

    /** The group's clearing account, which is a participant in a run's arithmetic and not a member of it. */
    private static final UUID FX_CLEARING = UUID.fromString("00000000-0000-0000-0000-00000000f0c1");

    @Autowired
    private DriveRuns driveRuns;

    @Test
    void twoAnswersFromTheApiSettleACycle() {
        ComputedRun run = runWaitingOnTheGroup();

        // Both of this run's members answer, through the API's write and nothing else. The run then needs no help
        // from this module: the driver's next pass finds quorum met, funding covered and a rail that settles INR,
        // and moves the money.
        answerAsTheApiDoes(run.run().id(), ALICE, "approve", AS_OF);
        answerAsTheApiDoes(run.run().id(), BORIS, "approve", AS_OF);

        driveRuns.driveOne(run.run().id());

        assertThat(storedRunStatus(run.run().id()))
                .as("a run approved from the API executed: this is the product gap the endpoint closes, and the two "
                        + "deployables settle a cycle without either knowing the other exists")
                .isEqualTo("done");
        assertThat(countIn("journal_entries"))
                .as("and the movement is booked: one entry per confirmed transfer, which is the settlement this "
                        + "whole path exists to produce")
                .isEqualTo(run.transfers().size());
    }

    @Test
    void oneAnswerIsNotAQuorumAndNothingMoves() {
        ComputedRun run = runWaitingOnTheGroup();

        answerAsTheApiDoes(run.run().id(), ALICE, "approve", AS_OF);

        driveRuns.driveOne(run.run().id());

        assertThat(storedRunStatus(run.run().id()))
                .as("ALICE is this run's only debtor, so her approval is the one the rule insists on - and the "
                        + "majority it also requires is still missing, which is why one member cannot move a group's "
                        + "money")
                .isEqualTo("awaiting_approval");
        assertThat(countIn("journal_entries"))
                .as("and nothing moved: a run that is waiting on the group has not booked a settlement")
                .isZero();
    }

    @Test
    void anAnswerFromSomebodyTheRunDoesNotConcernCountsForNothing() {
        ComputedRun run = runWaitingOnTheGroup();
        UUID stranger = UUID.randomUUID();

        // Two answers, and only one of them is the run's business: the stranger's row is written and stored - the
        // API records what a client claims - and the run's arithmetic ignores it.
        answerAsTheApiDoes(run.run().id(), ALICE, "approve", AS_OF);
        answerAsTheApiDoes(run.run().id(), stranger, "approve", AS_OF);

        driveRuns.driveOne(run.run().id());

        assertThat(countIn("settlement_decisions"))
                .as("the stranger's answer is on the record, because recording it is the API's job and judging it "
                        + "is this module's - and this module judges it by not counting it")
                .isEqualTo(2);
        assertThat(storedRunStatus(run.run().id()))
                .as("so the run is still waiting on BORIS rather than approved by a member it does not concern")
                .isEqualTo("awaiting_approval");
    }

    @Test
    void aRejectionFromTheApiEndsTheRunOnTheNextLook() {
        ComputedRun run = runWaitingOnTheGroup();
        UUID runId = run.run().id();

        answerAsTheApiDoes(runId, BORIS, "reject", AS_OF);

        assertThat(storedRunStatus(runId))
                .as("the API writes the decision row and nothing else (ADR 0020), so at this instant the run still "
                        + "reads awaiting_approval. The status is the worker's to write, and this is what it is like "
                        + "before the worker has looked")
                .isEqualTo("awaiting_approval");

        driveRuns.driveOne(runId);

        assertThat(storedRunStatus(runId))
                .as("BORIS is this run's only debtor, so his refusal ends the run: the driver offered it, execution "
                        + "refused it, and the refusal wrote the fate because a refusal is not a state - without the "
                        + "write this run would read awaiting_approval for ever while its own rows said the group "
                        + "said no")
                .isEqualTo("rejected");
        assertThat(countIn("journal_entries"))
                .as("and nothing moved: a rejection stops a settlement rather than undoing one")
                .isZero();
    }

    @Test
    void aRejectionFromSomebodyTheRunDoesNotConcernCountsForNothing() {
        ComputedRun run = runWaitingOnTheGroup();

        answerAsTheApiDoes(run.run().id(), UUID.randomUUID(), "reject", AS_OF);

        driveRuns.driveOne(run.run().id());

        assertThat(storedRunStatus(run.run().id()))
                .as("a refusal is read through the same intersection as an approval (ADR 0016), so somebody the run "
                        + "does not concern cannot stop a group's settlement. That symmetry is what makes a write "
                        + "from another deployable safe in both directions: the row is recorded by whoever holds the "
                        + "member's surface, and only the run's own arithmetic decides what it means")
                .isEqualTo("awaiting_approval");
    }

    @Test
    void anAnswerFromTheApiAndOneFromThisModuleAreTheSameArithmetic() {
        ComputedRun run = runWaitingOnTheGroup();

        // One member answers through the API's row, the other through this module's use case. Both writers, one
        // table, one set of rules: if either deployable had its own idea of what an answer means, this is where it
        // would show.
        answerAsTheApiDoes(run.run().id(), ALICE, "approve", AS_OF);
        ok(decideOnRun.decide(new RunDecision(run.run().id(), BORIS, RunDecision.Decision.APPROVE)));

        driveRuns.driveOne(run.run().id());

        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
    }

    @Test
    void anAnswerTheRunCannotHoldIsRefusedByItsOwnRows() {
        UUID runId = runWaitingOnTheGroup().run().id();

        assertThatThrownBy(() -> answerAsTheApiDoes(runId, FX_CLEARING, "approve", AS_OF))
                .as("the clearing account is a participant and not a member, and the run's own constraint says so. "
                        + "This is the refusal the API reports as a 422 rather than translating into a 500 - the "
                        + "database is the authority on what an answer can be, here and there")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(countIn("settlement_decisions")).isZero();
    }


    /**
     * A single-currency run waiting on its members: one commitment, planned and not yet answered.
     *
     * <p>Two participants and one debtor, so the rule under test is visible in both halves - a majority of the
     * members, and the debtor personally.
     */
    private ComputedRun runWaitingOnTheGroup() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);

        return computed(new RunRequest(GROUP, AS_OF));
    }

    /**
     * A member's answer, written exactly as {@code apps/api}'s {@code JooqDecisions} writes it.
     *
     * <p>Four columns and one word, and the word is a parameter because there are two of them: the API's endpoint
     * takes the word from the request, so both {@code 'approve'} and {@code 'reject'} cross this boundary and both
     * have to be judged here. The vocabulary is the run's - {@code V502}'s check constraint fixes it and
     * {@code Quorum} reads it - so an adapter in another deployable writes words it does not own.
     */
    private void answerAsTheApiDoes(UUID runId, UUID memberId, String decision, Instant at) {
        jdbc.update("""
                INSERT INTO settlement_decisions (run_id, member_id, decision, decided_at)
                VALUES (?, ?, ?, ?::timestamptz)
                """, runId, memberId, decision, at.atOffset(ZoneOffset.UTC));
    }
}
