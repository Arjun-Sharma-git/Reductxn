package io.reductxn.settlement;

import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opening a cycle on its own: saying a settlement is wanted, without computing it.
 *
 * <p><strong>What this test is defending is a separation.</strong> Until this port existed, a cycle and its plan
 * were written in one transaction, so "a cycle has come due" and "here is what it settles" were one act - and that
 * made a settlement policy inexpressible: a policy decides the instant, and the plan is a consequence of it. The
 * first test here asserts the thing that was impossible before: a cycle with no run over it. The last one asserts
 * the thing that makes the separation safe: the path that computes later finds that cycle rather than making a
 * second one.
 */
@SpringBootTest(classes = SettlementWorkerApplication.class)
class OpenCycleTest extends WorkerDatabaseTest {

    /** A group the fixtures know nothing about: no members, no commitments. */
    private static final UUID QUIET_GROUP = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Test
    void anOperatorOpensACycleAndNothingIsComputed() {
        assertThat(openCycle.open(new RunRequest(GROUP, AS_OF)))
                .as("the first open is this call's work")
                .isTrue();

        assertThat(countIn("settlement_cycles")).isEqualTo(1);
        assertThat(countIn("settlement_runs"))
                .as("opening is not computing: a plan is the cadence's work, and a cycle with no run is how a "
                        + "settlement policy says 'the books are closed, settle what is in them'")
                .isZero();
    }

    @Test
    void openingTheSameCycleTwiceIsOneRowAndTheSecondCallSaysSo() {
        openCycle.open(new RunRequest(GROUP, AS_OF));

        assertThat(openCycle.open(new RunRequest(GROUP, AS_OF)))
                .as("idempotent rather than refused: the cycle exists, which is what the caller asked for, and the "
                        + "answer says which call did the work")
                .isFalse();

        assertThat(countIn("settlement_cycles"))
                .as("a cycle is keyed by the group and the instant, so a retry collides at the database rather "
                        + "than in a check this call could lose a race to")
                .isEqualTo(1);
    }

    @Test
    void aCycleOpenedNowIsTheCycleComputedLater() {
        openCycle.open(new RunRequest(GROUP, AS_OF));

        UUID opened = jdbc.queryForObject("SELECT id FROM settlement_cycles", UUID.class);

        obligation(ALICE, BORIS, 5_000, "INR", 2);

        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        assertThat(run.run().cycleId())
                .as("the two paths meet on the group and the instant, not on an id: a cycle opened by an operator "
                        + "is the cycle this plan is over, and the insert that would have opened it found it there")
                .isEqualTo(opened);
        assertThat(run.run().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(countIn("settlement_cycles")).isEqualTo(1);
        assertThat(countIn("settlement_runs")).isEqualTo(1);
    }

    @Test
    void oneInstantInTwoGroupsIsTwoCycles() {
        openCycle.open(new RunRequest(GROUP, AS_OF));
        openCycle.open(new RunRequest(QUIET_GROUP, AS_OF));

        assertThat(countIn("settlement_cycles"))
                .as("the instant is not the key on its own - every group closes its own books, and two groups "
                        + "settling the same month are two cycles")
                .isEqualTo(2);
    }
}
