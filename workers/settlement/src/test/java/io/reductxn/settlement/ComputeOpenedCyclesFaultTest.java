package io.reductxn.settlement;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.application.ComputeRunService;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ComputedCycles;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunRequest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One cycle's fault, and what it must not do to the others.
 *
 * <p><strong>Why this is a class of its own.</strong> The fault has to be induced, and the only honest way to induce
 * one is a planner that throws - so this class registers a {@link ComputeRun} that delegates to the real service and
 * throws for one group. That makes the Spring context different from every other test's, which is why the
 * configuration is here rather than in the shared base: a faulting planner in {@code WorkerDatabaseTest} would be a
 * trap for every test that follows.
 *
 * <p><strong>What it proves is two things at once.</strong> The pass reports the fault rather than propagating it
 * (so the cycles after it are still attempted), and the plans the other cycles produced are still committed - which
 * is only true because {@code compute} is one transaction per cycle and the pass is not wrapped in one. Without
 * that, a single group with a broken book would stop every group behind it for ever: the cycle stays in the query,
 * so the pass would fault on it on every tick.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, ComputeOpenedCyclesFaultTest.FaultingPlanner.class})
class ComputeOpenedCyclesFaultTest extends WorkerDatabaseTest {

    /** The group whose plan cannot be computed. */
    private static final UUID BROKEN_GROUP = UUID.fromString("88888888-8888-4888-8888-888888888888");

    private static final Instant OPENED_AT = Instant.parse("2026-01-15T12:00:00Z");

    @Test
    void aFaultInOneCycleIsReportedAndThePassCarriesOn() {
        obligation(ALICE, BORIS, 5_000, "INR", 2);

        // The broken cycle first, so a pass that propagated the fault would never reach the second one.
        cycleOpenedAt(BROKEN_GROUP, AS_OF, OPENED_AT.minusSeconds(86_400));
        cycleOpenedAt(GROUP, AS_OF, OPENED_AT);

        ComputedCycles report = computeOpenedCycles.computeOpened(10);

        assertThat(report.outcomes()).hasSize(2);
        assertThat(report.faulted()).isEqualTo(1);
        assertThat(report.computed())
                .as("the cycle after the broken one was still attempted")
                .isEqualTo(1);
        assertThat(report.outcomes().get(0).fault())
                .as("named, so an operator knows what to look at rather than that something happened")
                .contains("IllegalStateException")
                .contains("this group's book cannot be planned");
        assertThat(countIn("settlement_runs"))
                .as("and the plan that succeeded is committed: one transaction per cycle is what makes a broken "
                        + "group its own problem instead of every group's")
                .isEqualTo(1);
    }

    /** A cycle written at an instant the test chooses: the order the pass works in is by that column. */
    private void cycleOpenedAt(UUID groupId, Instant asOf, Instant openedAt) {
        jdbc.update("INSERT INTO settlement_cycles (id, group_id, as_of, created_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), groupId, OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC));
    }

    /**
     * The real use case, with one group's plan throwing.
     *
     * <p>A delegating stub rather than a mock, for the reason the module's other stubs are hand-written: what is
     * being tested is what the pass does with a fault, and a stub that threw for everything would not show that the
     * third cycle of a batch still gets its plan.
     */
    @TestConfiguration
    static class FaultingPlanner {

        @Bean
        @Primary
        ComputeRun faultingComputeRun(ComputeRunService real) {
            return (RunRequest request) -> {
                if (request.groupId().equals(BROKEN_GROUP)) {
                    throw new IllegalStateException("this group's book cannot be planned");
                }

                return real.compute(request);
            };
        }
    }
}
