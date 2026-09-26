package io.reductxn.settlement.adapter.command;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.DrivenRuns;
import io.reductxn.settlement.port.in.Escalation;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.PartitionOutcome;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.ReadStuckTransfers;
import io.reductxn.settlement.port.in.Reconciliation;
import io.reductxn.settlement.port.in.RecordFxFunding;
import io.reductxn.settlement.port.in.ResolveTransfer;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.StuckTransfer;
import io.reductxn.settlement.port.in.StuckTransfers;
import io.reductxn.settlement.port.in.TransferResolution;
import io.reductxn.settlement.port.in.TransferStatus;

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
 *
 *   java -jar workers-settlement.jar --reductxn.command=escalations [--reductxn.limit=&lt;n&gt;]
 *
 *   java -jar workers-settlement.jar --reductxn.command=resolve --reductxn.run=&lt;uuid&gt; \
 *        --reductxn.transfer=&lt;uuid&gt; --reductxn.outcome=confirmed|failed \
 *        --reductxn.by=&lt;who&gt; --reductxn.basis=&lt;what they looked at&gt;
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
 * <p><strong>And the one place an operator does decide: {@code resolve}.</strong> A transfer no rail will answer
 * cannot be decided by the worker (ADR 0024), so somebody who has asked the rail says what it turned out to be.
 * That command is the exception that proves the rule above: it does not act on a member's behalf, it records
 * something a person knows and names them, and the name is what makes the keystroke accountable. It takes the
 * basis the person acted on as well, because the write can complete a run or end one.
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

    /**
     * How many stuck transfers the listing shows unless the operator asks for another number.
     *
     * <p>Fifty is a screenful and a half: enough to see the shape of a problem, few enough to read. The report
     * always says how many there are in total, so this limit never hides the size of it.
     */
    private static final int DEFAULT_ESCALATION_LIMIT = 50;

    private final ComputeRun computeRun;
    private final DriveRuns driveRuns;
    private final RecordFxFunding recordFxFunding;
    private final ReadStuckTransfers readStuckTransfers;
    private final Reconciliation reconciliation;
    private final ResolveTransfer resolveTransfer;
    private final Escalation escalation;
    private final Clock clock;
    private final Duration window;
    private final int batchSize;

    public SettlementCommand(
            ComputeRun computeRun,
            DriveRuns driveRuns,
            RecordFxFunding recordFxFunding,
            ReadStuckTransfers readStuckTransfers,
            Reconciliation reconciliation,
            ResolveTransfer resolveTransfer,
            Escalation escalation,
            Clock clock,
            @Value("${reductxn.settlement.drive.window:PT2M}") Duration window,
            @Value("${reductxn.settlement.drive.batch-size:50}") int batchSize) {

        this.computeRun = computeRun;
        this.driveRuns = driveRuns;
        this.recordFxFunding = recordFxFunding;
        this.readStuckTransfers = readStuckTransfers;
        this.reconciliation = reconciliation;
        this.resolveTransfer = resolveTransfer;
        this.escalation = escalation;
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
            case "escalations" -> escalations(args, out);
            case "resolve" -> resolve(args, out);
            default -> usage(out, "unknown command '" + command.get(0)
                    + "'. This worker knows: compute, drive, fund, escalations, resolve");
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

    /**
     * What no rail has decided: the listing behind section 4's "or escalate".
     *
     * <p><strong>A read, and it exits zero even when the answer is bad.</strong> The command did what it was asked -
     * it looked - and an operator scripting this wants the report rather than an error: "nothing has been stuck long
     * enough" is the good news, and a refusal code would make a monitoring script treat good news as a fault.
     * The exit codes still mean what they mean elsewhere: {@code 2} for an invocation that cannot be understood.
     *
     * <p><strong>One listing with two reasons, because the operator has one question.</strong> A transfer gets stuck
     * in two ways - no call is answered ({@code no_answer}) or the rail keeps answering "still pending" and never
     * confirms ({@code no_confirmation}) - and both mean nobody is waiting for the movement any more. Two commands
     * would mean remembering to run both; one listing with the reason on each line means the person on call sees the
     * whole problem, oldest first (ADR 0026).
     *
     * <p><strong>Nothing here decides anything, and the output says so.</strong> The worker cannot resolve a stuck
     * transfer: it does not know whether the money moved, and marking one failed or confirmed would be asserting a
     * fact nobody has. What this prints is what the worker holds - the rail's own last words included - so the person
     * who can find out (by asking the rail) knows which movement to ask about.
     */
    private int escalations(ApplicationArguments args, PrintStream out) {
        int limit = DEFAULT_ESCALATION_LIMIT;

        if (option(args, "limit").isPresent()) {
            try {
                limit = Integer.parseInt(option(args, "limit").orElseThrow());
            } catch (IllegalArgumentException malformed) {
                return usage(out, "limit must be a whole number of transfers: " + malformed.getMessage());
            }
        }

        Instant now = clock.instant();
        StuckTransfers stuck = readStuckTransfers.stuckLongerThan(
                escalation.cutOff(now), reconciliation.cutOff(now), limit);

        if (stuck.total() == 0) {
            out.printf("escalations: no transfer has gone unanswered for longer than %s, or sat at the rail "
                    + "unconfirmed for longer than %s%n", escalation.after(), reconciliation.after());
            return OK;
        }

        out.printf("escalations: %d transfer(s) no rail has decided (silent before %s, unconfirmed before %s)%s%n",
                stuck.total(), stuck.unansweredCutOff(), stuck.unconfirmedCutOff(),
                stuck.truncated() ? ", oldest " + stuck.transfers().size() + " listed" : "");

        for (StuckTransfer transfer : stuck.transfers()) {
            out.printf("  %s: %s %s, %s for %s, %s%n", transfer.railRef(), transfer.amount().amountMinor(),
                    transfer.currency().code(), transfer.reason().wireName(), transfer.stuckFor(now),
                    transfer.unansweredAttempts() == 0
                            ? "the rail is answering"
                            : transfer.unansweredAttempts() + " call(s) in a row unanswered");
            out.printf("    %s, run %s of group %s, transfer %s%n", transfer.status().wireName(),
                    transfer.runId(), transfer.groupId(), transfer.transferId());

            if (transfer.railDetail() != null) {
                out.printf("    the rail last said: %s%n", transfer.railDetail());
            }
        }

        out.println("Whether the money moved is the rail's answer to give. Ask the rail about a transfer it has "
                + "(no_confirmation) first: it accepted the instruction, so it can say what became of it. Then record "
                + "what you find with --reductxn.command=resolve --reductxn.run=<run> --reductxn.transfer=<transfer> "
                + "--reductxn.outcome=confirmed|failed --reductxn.by=<you> --reductxn.basis=<what you looked at>.");

        return OK;
    }

    /**
     * Records what a person found out about a transfer no rail would decide: the way back from {@code escalations}.
     *
     * <p><strong>Two options exist for one reason: the write is a claim about money.</strong> {@code by} says who is
     * making it and {@code basis} says what they were looking at, and the use case refuses either being blank. That
     * is the whole of this project's answer to "who may assert that money moved": a person may, and only in a form
     * that names them. Nothing checks the name - there are no identities in this build - so this is an attribution
     * rather than an authorisation, and a deployment that needs the difference has to bring it.
     *
     * <p><strong>It is the one command that writes a fate rather than triggering work</strong>, and it says what it
     * did: which transfer, in which run, who asserted it, and whether the ledger now holds a movement. A refusal
     * (already answered, never offered to a rail, no such transfer) is the domain saying no and exits {@code 1},
     * exactly as the other commands' refusals do.
     */
    private int resolve(ApplicationArguments args, PrintStream out) {
        OptionalInt missing = require(args, out, "run", "transfer", "outcome", "by", "basis");
        if (missing.isPresent()) {
            return missing.getAsInt();
        }

        UUID runId;
        UUID transferId;
        TransferResolution.Outcome outcome;

        try {
            runId = UUID.fromString(option(args, "run").orElseThrow());
            transferId = UUID.fromString(option(args, "transfer").orElseThrow());
            outcome = TransferResolution.Outcome.ofWireName(option(args, "outcome").orElseThrow());
        } catch (IllegalArgumentException malformed) {
            return usage(out, "run and transfer must be uuids and outcome 'confirmed' or 'failed': "
                    + malformed.getMessage());
        }

        Result<ComputedRun> resolved = resolveTransfer.resolve(new TransferResolution(
                runId, transferId, outcome, option(args, "by").orElseThrow(), option(args, "basis").orElseThrow()));

        if (resolved instanceof Result.Failure<ComputedRun> refusal) {
            return refused(out, refusal.error().code() + ": " + refusal.error().message());
        }

        ComputedRun run = resolved.orElseThrow();
        PlannedTransfer transfer = run.transfers().stream()
                .filter(each -> each.id().equals(transferId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "transfer " + transferId + " was resolved and is not in the run read back from its rows"));

        out.printf("resolve: transfer %s is %s%n", transfer.railRef(), transfer.status().wireName());
        out.printf("  %s from %s to %s, in run %s of group %s%n", minorUnits(transfer.amount()),
                transfer.fromUserId(), transfer.toUserId(), run.run().id(), run.run().groupId());
        out.printf("  asserted by %s at %s, on the basis of %s%n",
                transfer.resolvedBy(), transfer.resolvedAt(), transfer.resolvedBasis());
        out.printf("  the ledger %s%n", transfer.status() == TransferStatus.CONFIRMED
                ? "now holds the movement: one settlement entry, derived from this transfer"
                : "holds nothing new: the money did not move, so there is no posting to make");

        long answered = run.transfers().stream().filter(each -> each.status().isResolved()).count();

        out.printf("run %s is %s: %d of %d transfer(s) answered%n",
                run.run().id(), run.run().status().wireName(), answered, run.transfers().size());

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
