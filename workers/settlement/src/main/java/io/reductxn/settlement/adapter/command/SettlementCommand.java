package io.reductxn.settlement.adapter.command;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.DrivenRuns;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.PartitionOutcome;
import io.reductxn.settlement.port.in.RecordFxFunding;
import io.reductxn.settlement.port.in.RunRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The operator's command line: how a human makes this worker do something now.
 *
 * <pre>
 *   java -jar workers-settlement.jar --reductxn.command=compute \
 *        --reductxn.group=&lt;uuid&gt; [--reductxn.as-of=&lt;instant&gt;]
 *
 *   java -jar workers-settlement.jar --reductxn.command=drive [--reductxn.run=&lt;uuid&gt;]
 *
 *   java -jar workers-settlement.jar --reductxn.command=fund --reductxn.run=&lt;uuid&gt; \
 *        --reductxn.currency=JPY --reductxn.amount-minor=224 --reductxn.funding-ref=&lt;reference&gt;
 * </pre>
 *
 * <p><strong>Why a command line rather than an endpoint.</strong> This module has no HTTP surface and must not
 * grow one: an endpoint here would be a second write path to the same rows, without the API's validation,
 * idempotency or error vocabulary. A command line is not a network surface - it is an operator with a shell -
 * and it is the shape that fits the two things that are not automatic: opening a cycle (when a cycle is due is a
 * group's schedule, and group schedules do not exist) and recording that currency arrived (a treasury fact
 * somebody observed).
 *
 * <p><strong>What is deliberately not here: approving a run.</strong> {@code DecideOnRun} is a member's act, and
 * a command that approved on a member's behalf would let an operator settle a group's money with a keystroke no
 * member made. Until members exist and the API can carry their answers, approvals are recorded through the port
 * (which is what the tests do) and this command refuses to pretend otherwise.
 *
 * <p><strong>Exit codes are part of the interface</strong>, because a shell is a client: {@code 0} when the
 * command did what it was asked, {@code 1} when the domain refused - no such run, nothing to settle, a quorum
 * that is not met - and {@code 2} when the invocation itself was wrong. Anything that treats a refusal as a
 * crash, or a typo as a refusal, would be a script that does the wrong thing quietly.
 *
 * <p>Output is written through a {@link PrintStream} the caller supplies, so the report can be asserted in a test
 * rather than being observable only on somebody's terminal.
 */
@Component
public class SettlementCommand {

    /** The option that makes this a command rather than a service. Absent means "run as a worker". */
    public static final String COMMAND = "reductxn.command";

    public static final int OK = 0;
    public static final int REFUSED = 1;
    public static final int USAGE = 2;

    private final ComputeRun computeRun;
    private final DriveRuns driveRuns;
    private final RecordFxFunding recordFxFunding;
    private final Clock clock;
    private final Duration window;
    private final int batchSize;

    public SettlementCommand(
            ComputeRun computeRun,
            DriveRuns driveRuns,
            RecordFxFunding recordFxFunding,
            Clock clock,
            @Value("${reductxn.settlement.drive.window:PT2M}") Duration window,
            @Value("${reductxn.settlement.drive.batch-size:50}") int batchSize) {

        this.computeRun = computeRun;
        this.driveRuns = driveRuns;
        this.recordFxFunding = recordFxFunding;
        this.clock = clock;
        this.window = window;
        this.batchSize = batchSize;
    }

    /**
     * Runs the command if the arguments name one.
     *
     * @return the exit code, or empty when this is not a command invocation - in which case the application
     *         carries on as a worker and the caller must not exit
     */
    public OptionalInt execute(ApplicationArguments args, PrintStream out) {
        List<String> command = args.getOptionValues(COMMAND);

        if (command == null || command.isEmpty()) {
            return OptionalInt.empty();
        }

        return OptionalInt.of(switch (command.get(0)) {
            case "compute" -> compute(args, out);
            case "drive" -> drive(args, out);
            case "fund" -> fund(args, out);
            default -> usage(out, "unknown command '" + command.get(0)
                    + "'. This worker knows: compute, drive, fund");
        });
    }

    /**
     * Opens a cycle for a group and prints the plan.
     *
     * <p>The one thing an operator has to supply that nothing else can: <em>when</em> a cycle is due. A group's
     * settlement schedule does not exist yet (groups do not), so the alternative to this argument would be a
     * policy invented here - "settle whatever is open, every time the job runs" - which is a decision about a
     * group's money that this slice is in no position to make.
     */
    private int compute(ApplicationArguments args, PrintStream out) {
        OptionalInt missing = require(args, out, "group");
        if (missing.isPresent()) {
            return missing.getAsInt();
        }

        UUID groupId;
        Instant asOf;

        try {
            groupId = UUID.fromString(option(args, "group").orElseThrow());
            asOf = option(args, "as-of").map(Instant::parse).orElseGet(clock::instant);
        } catch (IllegalArgumentException malformed) {
            return usage(out, "group must be a uuid and as-of an ISO-8601 instant: " + malformed.getMessage());
        }

        Result<ComputedRun> computed = computeRun.compute(new RunRequest(groupId, asOf));

        if (computed instanceof Result.Failure<ComputedRun> refusal) {
            return refused(out, refusal.error().code() + ": " + refusal.error().message());
        }

        ComputedRun run = computed.orElseThrow();

        out.printf("compute: group %s, cycle as of %s%n", groupId, asOf);
        out.printf("run %s is %s%n", run.run().id(), run.run().status().wireName());

        for (PartitionOutcome partition : run.partitions()) {
            out.printf("  %s: %d member transfer(s) and %d clearing leg(s), %s net, %s tax%n",
                    partition.currency().code(), partition.memberTransferCount(),
                    partition.clearingTransferCount(), minorUnits(partition.net()),
                    minorUnits(partition.tax()));
        }

        for (Money requirement : run.funding().required()) {
            out.printf("  requires %s externally, in %s%n",
                    requirement.amountMinor(), requirement.currency().code());
        }

        out.printf("  quorum: %d approval(s) of %d participant(s), %s%n",
                run.quorum().approvers().size(), run.quorum().members().size(),
                run.quorum().met() ? "met" : "not met");

        return OK;
    }

    /**
     * Drives the runs whose progress is possible: one pass, now.
     *
     * <p>Without {@code --reductxn.run} this behaves as one tick of the cadence, window included - which is what
     * makes it safe to put in a cron job beside a daemon, or to run by hand while one is running. With a run
     * named, the window does not apply: an operator asking for one specific run is not a stampede, and refusing
     * on the grounds that it was attempted recently would make the command useless in the case it exists for.
     */
    private int drive(ApplicationArguments args, PrintStream out) {
        Optional<String> named = option(args, "run");

        if (named.isPresent()) {
            UUID runId;

            try {
                runId = UUID.fromString(named.get());
            } catch (IllegalArgumentException malformed) {
                return usage(out, "run must be a uuid: " + malformed.getMessage());
            }

            return answer(driveRuns.driveOne(runId), out);
        }

        return answer(driveRuns.driveDue(clock.instant().minus(window), batchSize), out);
    }

    /**
     * Records currency arriving, so a run blocked on RULE S4's requirement can proceed.
     *
     * <p>{@code funding-ref} is required rather than derived. It is the reference the arrival is known by outside
     * reductxn - a bank line, a provider's confirmation - and it is also the idempotency key: two identical
     * arrivals with the same reference are one arrival, and a reference derived from the amount would make two
     * genuine arrivals of the same amount indistinguishable.
     */
    private int fund(ApplicationArguments args, PrintStream out) {
        OptionalInt missing = require(args, out, "run", "currency", "amount-minor", "funding-ref");
        if (missing.isPresent()) {
            return missing.getAsInt();
        }

        UUID runId;
        Currency currency;
        long amountMinor;

        try {
            runId = UUID.fromString(option(args, "run").orElseThrow());
            currency = Currency.of(option(args, "currency").orElseThrow(),
                    Integer.parseInt(option(args, "exponent").orElse("2")));
            amountMinor = Long.parseLong(option(args, "amount-minor").orElseThrow());
        } catch (IllegalArgumentException malformed) {
            return usage(out, "run must be a uuid, amount-minor a whole number of minor units: "
                    + malformed.getMessage());
        }

        Money arrived = Money.of(amountMinor, currency);

        Result<ComputedRun> funded = recordFxFunding.fund(new FundingRequest(
                runId, currency, arrived, option(args, "funding-ref").orElseThrow()));

        if (funded instanceof Result.Failure<ComputedRun> refusal) {
            return refused(out, refusal.error().code() + ": " + refusal.error().message());
        }

        ComputedRun run = funded.orElseThrow();

        out.printf("fund: %s recorded against run %s%n", minorUnits(arrived), runId);
        out.printf("run is %s; %s%n", run.run().status().wireName(),
                run.funding().outstanding().isEmpty()
                        ? "nothing further required"
                        : "still required: " + run.funding().outstanding().stream()
                                .map(amount -> amount.amountMinor() + " " + amount.currency().code())
                                .toList());

        return OK;
    }

    /** A driven pass, or the refusal that stopped it. */
    private int answer(Result<DrivenRuns> driven, PrintStream out) {
        if (driven instanceof Result.Failure<DrivenRuns> refusal) {
            return refused(out, refusal.error().code() + ": " + refusal.error().message());
        }

        DrivenRuns report = driven.orElseThrow();

        out.printf("drive: %d run(s) considered, %d moved, %d left alone, %d completed, %d failed%n",
                report.outcomes().size(), report.driven(), report.refused(),
                report.completed().size(), report.failed().size());

        for (DrivenRuns.RunOutcome outcome : report.outcomes()) {
            out.printf("  run %s is %s: %s%n", outcome.runId(), outcome.status().wireName(),
                    outcome.driven() ? "driven" : outcome.reason());
        }

        return OK;
    }

    private int refused(PrintStream out, String reason) {
        out.println("refused: " + reason);
        return REFUSED;
    }

    private int usage(PrintStream out, String problem) {
        out.println("usage error: " + problem);
        return USAGE;
    }

    /** A required option, or a usage error naming it. */
    private OptionalInt require(ApplicationArguments args, PrintStream out, String... options) {
        for (String option : options) {
            if (option(args, option).isEmpty()) {
                return OptionalInt.of(usage(out, "--reductxn." + option + " is required by this command"));
            }
        }

        return OptionalInt.empty();
    }

    private Optional<String> option(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues("reductxn." + name);

        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /** Minor units with their currency: a report that formatted money would need the group's locale. */
    private static String minorUnits(Money money) {
        return money.amountMinor() + " " + money.currency().code();
    }
}
