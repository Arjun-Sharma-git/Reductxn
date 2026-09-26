package io.reductxn.settlement;

import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.out.Runs;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What the gates concluded, written down where a reader in another deployable can project it.
 *
 * <p><strong>This class is about the one thing the worker stores for someone else's benefit.</strong> A member
 * asking why a run is waiting is asking this module's arithmetic - quorum needs the participants and who owes what,
 * funding needs the clearing positions and what has arrived - and {@code apps/api} may not derive it again without
 * a second implementation of the rule (ADR 0021). So the module that owns the rule writes its verdict down
 * (ADR 0023), and the tests here are about the three things that makes true: the verdict says what the gates would
 * say, it is as fresh as the last look, and it carries the version of the run it was reached for.
 *
 * <p>The assertions read the stored columns rather than a returned object, because the columns are what the other
 * deployable projects - and because a verdict returned by the writer would be derived from the same rows a second
 * time and agree with itself. {@code ReadRunApiTest} writes these same rows by SQL and pins the reading side.
 *
 * <p>The clock is the movable one: "as fresh as the last look" is a statement about time, and a test that had to
 * sleep to make it would not be written.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class,
        MovingClockConfiguration.class})
class GateConclusionTest extends WorkerDatabaseTest {

    @Autowired
    private DriveRuns driveRuns;

    @Autowired
    private Runs runs;

    @Autowired
    private MovingClockConfiguration.MovingClock clock;

    @Test
    void aRunWaitingOnItsGroupStoresWhatTheGatesSaid() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));

        ok(driveRuns.driveOne(run.run().id()));

        Map<String, Object> stored = storedGates(run.run().id());

        assertThat(stored.get("status")).isEqualTo("awaiting_approval");
        assertThat(stored.get("quorum_met")).isEqualTo(false);
        assertThat(stored.get("approvals_counted")).isEqualTo(0);
        assertThat(stored.get("approvals_required"))
                .as("a majority of the run's two members, which is the number that makes 'one approval short' "
                        + "sayable without a reader implementing the majority rule a second time")
                .isEqualTo(2);
        assertThat(stored.get("debtors_still_to_confirm"))
                .as("ALICE owes the net amount, so the rule is waiting on her personally as well as on the majority")
                .isEqualTo(1);
        assertThat(stored.get("funding_satisfied"))
                .as("nothing external is required - and a satisfied gate is a verdict rather than an absent one, "
                        + "because a reader has to tell 'covered' from 'not concluded yet'")
                .isEqualTo(true);
        assertThat(countIn("settlement_run_gate_shortfalls")).isZero();
    }

    @Test
    void aRunWaitingOnCurrencyStoresWhatIsStillMissing() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        ok(driveRuns.driveOne(run.run().id()));

        assertThat(storedShortfalls(run.run().id()))
                .as("per currency, in the plan's order, and only the gaps - a covered currency has no row, which "
                        + "is the choice Funding.outstanding() already makes")
                .containsExactly(tuple("INR", 12_500L), tuple("JPY", 224L));
        assertThat(storedGates(run.run().id()).get("funding_satisfied")).isEqualTo(false);
        assertThat(storedGates(run.run().id()).get("quorum_met"))
                .as("two questions, two answers: every member approved, and the group still has to bring currency "
                        + "in from outside (RULE S4)")
                .isEqualTo(true);
    }

    @Test
    void theConclusionIsAsFreshAsTheLastLook() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        UUID runId = computed(new RunRequest(GROUP, AS_OF)).run().id();

        ok(driveRuns.driveOne(runId));
        Instant firstLook = storedInstant(runId);

        clock.advance(Duration.ofMinutes(5));
        ok(driveRuns.driveOne(runId));

        assertThat(countIn("settlement_run_gates"))
                .as("one row per run, replaced in place: a reader that had to choose between two conclusions would "
                        + "need a rule about which one is current")
                .isEqualTo(1);
        assertThat(storedInstant(runId))
                .as("rewritten on every look, so concludedAt means 'when the worker last read these gates' rather "
                        + "than 'when this verdict was first reached'")
                .isAfter(firstLook);
    }

    @Test
    void theStoredConclusionSaysWhatTheRunWouldSay() {
        // Two derivations of one fact: this module reads the verdicts from the rows on every read, and the row is
        // the copy it writes for someone else. They are compared rather than assumed equal, because a projection
        // that drifts from the rule it projects is the failure this arrangement risks.
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        decided(run.run().id(), ALICE, RunDecision.Decision.APPROVE);

        ok(driveRuns.driveOne(run.run().id()));

        ComputedRun fresh = runs.findRunById(run.run().id()).orElseThrow();
        Map<String, Object> stored = storedGates(run.run().id());

        assertThat(stored.get("quorum_met")).isEqualTo(fresh.quorum().met());
        assertThat(stored.get("approvals_counted")).isEqualTo(fresh.quorum().countedApprovals().size());
        assertThat(stored.get("approvals_required")).isEqualTo(fresh.quorum().required());
        assertThat(stored.get("debtors_still_to_confirm")).isEqualTo(fresh.quorum().outstandingDebtors().size());
        assertThat(stored.get("funding_satisfied")).isEqualTo(fresh.funding().satisfied());
        assertThat(storedShortfalls(run.run().id()))
                .containsExactlyElementsOf(fresh.funding().outstanding().stream()
                        .map(outstanding -> tuple(outstanding.currency().code(), outstanding.amountMinor()))
                        .toList());
    }

    @Test
    void aConclusionThatOutlivesItsRunCarriesTheVersionItWasReachedFor() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        // One pass: the gates are read (quorum met, nothing to fund) and the run executes and finishes.
        ok(driveRuns.driveOne(run.run().id()));

        assertThat(storedRunStatus(run.run().id())).isEqualTo("done");
        assertThat(storedGates(run.run().id()).get("status"))
                .as("awaiting_approval, which is what the run was when the gates were read. A projection that "
                        + "outlives its subject has to say which version of it the verdict belongs to, or a finished "
                        + "run would read as one approval short for ever - and a reader compares this with the run's "
                        + "current status to tell a current conclusion from a stale one")
                .isEqualTo("awaiting_approval");
    }

    /** The conclusion as stored: the columns the other deployable projects. */
    private Map<String, Object> storedGates(UUID runId) {
        return jdbc.queryForMap(
                "SELECT status, quorum_met, approvals_counted, approvals_required, debtors_still_to_confirm, "
                        + "funding_satisfied, concluded_at FROM settlement_run_gates WHERE run_id = ?",
                runId);
    }

    /** The funding gaps as stored, in currency order. */
    private List<Tuple> storedShortfalls(UUID runId) {
        return jdbc.query(
                "SELECT currency_code, amount_minor FROM settlement_run_gate_shortfalls WHERE run_id = ? "
                        + "ORDER BY currency_code",
                (rows, index) -> tuple(rows.getString(1), rows.getLong(2)),
                runId);
    }

    private Instant storedInstant(UUID runId) {
        return ((java.sql.Timestamp) storedGates(runId).get("concluded_at")).toInstant();
    }
}
