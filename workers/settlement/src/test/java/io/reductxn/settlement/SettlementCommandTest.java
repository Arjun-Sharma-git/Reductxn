package io.reductxn.settlement;

import io.reductxn.settlement.adapter.command.SettlementCommand;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operator's command line: what it does, what it says, and what it refuses.
 *
 * <p>Asserted through the real beans against the real database, because the command's job is to reach the same
 * use cases an endpoint would - a command tested over stubs would prove its formatting and nothing about whether
 * a group's money moves. The output is asserted too: an operator surface is only as good as what it prints, and
 * a report nobody checks is how a command ends up printing the wrong run id for a year.
 *
 * <p>The exit codes are part of that surface. {@code 0}, {@code 1} and {@code 2} mean three different things to
 * a shell, and the distinction between "the domain refused" and "you typed it wrong" is the one that matters: a
 * cron job that treats a refusal as a crash, and a crash as a refusal, will do the wrong thing quietly.
 */
@SpringBootTest(classes = {SettlementWorkerApplication.class, StubRailConfiguration.class})
class SettlementCommandTest extends WorkerDatabaseTest {

    @Autowired
    private SettlementCommand command;

    @Autowired
    private StubRail rail;

    private ByteArrayOutputStream output;

    @BeforeEach
    void resetTheRailAndTheTerminal() {
        rail.reset();
        output = new ByteArrayOutputStream();
    }

    @Test
    void noCommandMeansTheWorkerRunsAsAWorker() {
        OptionalInt code = command.execute(args("--spring.profiles.active=test"), terminal());

        assertThat(code)
                .as("empty rather than zero: the caller must not exit the JVM, because there is nothing to "
                        + "exit after - the cadence is the process's reason to exist")
                .isEmpty();
    }

    @Test
    void computeOpensACycleAndPrintsThePlan() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);

        OptionalInt code = command.execute(
                args("--reductxn.command=compute", "--reductxn.group=" + GROUP, "--reductxn.as-of=" + AS_OF),
                terminal());

        assertThat(code).hasValue(SettlementCommand.OK);
        assertThat(text())
                .contains("compute: group " + GROUP)
                .contains("INR: 1 member transfer(s) and 0 clearing leg(s)")
                .contains("quorum: 0 approval(s) of 2 participant(s), not met");
        assertThat(countIn("settlement_runs")).as("and the run is really there").isEqualTo(1);
    }

    @Test
    void computeOnAGroupWithNothingToSettleIsRefusedRatherThanEmpty() {
        OptionalInt code = command.execute(
                args("--reductxn.command=compute", "--reductxn.group=" + GROUP), terminal());

        assertThat(code).hasValue(SettlementCommand.REFUSED);
        assertThat(text()).contains("refused: settlement.nothing_to_settle");
    }

    @Test
    void aWrongInvocationIsNotARefusal() {
        assertThat(command.execute(args("--reductxn.command=compute"), terminal()))
                .as("a missing option is a usage error, not the domain saying no")
                .hasValue(SettlementCommand.USAGE);
        assertThat(command.execute(
                args("--reductxn.command=compute", "--reductxn.group=not-a-uuid"), terminal()))
                .hasValue(SettlementCommand.USAGE);
        assertThat(command.execute(args("--reductxn.command=reticulate"), terminal()))
                .hasValue(SettlementCommand.USAGE);
        assertThat(text()).contains("usage error:");
    }

    @Test
    void fundRecordsAnArrivalAndReportsWhatIsLeft() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);
        UUID runId = ok(computeRun.compute(new RunRequest(GROUP, AS_OF))).run().id();

        OptionalInt code = command.execute(args(
                "--reductxn.command=fund",
                "--reductxn.run=" + runId,
                "--reductxn.currency=INR",
                "--reductxn.amount-minor=12500",
                "--reductxn.funding-ref=bank-line-1"), terminal());

        assertThat(code).hasValue(SettlementCommand.OK);
        assertThat(text())
                .as("an operator who has watched one currency arrive is told what the run is still waiting for")
                .contains("still required: [224 JPY]");

        // The same arrival again is one arrival: `funding_ref` is the caller's idempotency key (RULE S2), and a
        // treasury reference re-sent after a timeout is the ordinary case that key exists for.
        assertThat(command.execute(args(
                "--reductxn.command=fund",
                "--reductxn.run=" + runId,
                "--reductxn.currency=INR",
                "--reductxn.amount-minor=12500",
                "--reductxn.funding-ref=bank-line-1"), terminal()))
                .hasValue(SettlementCommand.OK);
        assertThat(countIn("settlement_fx_fundings")).isEqualTo(1);
    }

    @Test
    void anOperatorSettlesAGroupInTwoCommandsOnceApprovalsExist() {
        crossCurrencyObligation(ALICE, BORIS, 12_500L, 224L);

        assertThat(command.execute(
                args("--reductxn.command=compute", "--reductxn.group=" + GROUP, "--reductxn.as-of=" + AS_OF),
                terminal())).hasValue(SettlementCommand.OK);

        UUID runId = jdbc.queryForObject("SELECT id FROM settlement_runs", UUID.class);

        // Approvals are recorded through the port, because approving is a member's act and this command refuses
        // to pretend otherwise. That the money then moves from a shell is the point; that a member has to be
        // simulated first is the gap this test makes visible rather than hides.
        for (UUID member : List.of(ALICE, BORIS)) {
            ok(decideOnRun.decide(new RunDecision(runId, member, RunDecision.Decision.APPROVE)));
        }

        assertThat(command.execute(args(
                "--reductxn.command=fund",
                "--reductxn.run=" + runId,
                "--reductxn.currency=INR", "--reductxn.amount-minor=12500",
                "--reductxn.funding-ref=bank-line-inr"), terminal())).hasValue(SettlementCommand.OK);
        assertThat(command.execute(args(
                "--reductxn.command=fund",
                "--reductxn.run=" + runId,
                "--reductxn.currency=JPY", "--reductxn.amount-minor=224", "--reductxn.exponent=0",
                "--reductxn.funding-ref=bank-line-jpy"), terminal())).hasValue(SettlementCommand.OK);

        OptionalInt code = command.execute(
                args("--reductxn.command=drive", "--reductxn.run=" + runId), terminal());

        assertThat(code).hasValue(SettlementCommand.OK);
        assertThat(text()).contains("is done");
        assertThat(storedRunStatus(runId)).isEqualTo("done");
        assertThat(rail.submissions()).as("the money moved, from a shell").hasSize(2);
    }

    @Test
    void drivingARunThatDoesNotExistIsRefused() {
        OptionalInt code = command.execute(
                args("--reductxn.command=drive", "--reductxn.run=" + new UUID(0L, 999L)), terminal());

        assertThat(code).hasValue(SettlementCommand.REFUSED);
        assertThat(text()).contains("refused: settlement.run_not_found");
    }

    @Test
    void drivingWithNothingDueSaysSoWithoutFailing() {
        OptionalInt code = command.execute(args("--reductxn.command=drive"), terminal());

        assertThat(code)
                .as("an idle worker is not an error: a cron job that failed whenever there was nothing to do "
                        + "would train whoever reads the alerts to ignore them")
                .hasValue(SettlementCommand.OK);
        assertThat(text()).contains("0 run(s) considered");
    }

    private PrintStream terminal() {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }

    private String text() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private static DefaultApplicationArguments args(String... options) {
        return new DefaultApplicationArguments(options);
    }
}
