package io.reductxn.rails.internal.breaker;

import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.rails.api.BreakerPolicy;
import io.reductxn.rails.api.PaymentRail;
import io.reductxn.rails.api.PaymentRail.RailUnreachable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A rail that is left alone after it keeps failing, reached through
 * {@link io.reductxn.rails.api.Rails#withCircuitBreaker} (RULE B10).
 *
 * <p><strong>Three states, and the middle one is the interesting one.</strong> Closed: calls go through, and
 * consecutive unanswered calls are counted. Open: nothing is called, and every call answers with a retryable
 * refusal - so a caller behaves exactly as it would against a rail that is not answering, which is the truth.
 * Half-open: after the cooldown, exactly <em>one</em> call is allowed through as a probe. If it is answered at
 * all - including a terminal refusal, which is a rail with an opinion and therefore alive - the breaker closes
 * and the count resets. If it is not answered, the breaker opens again for another cooldown.
 *
 * <p><strong>An open breaker answers in the port's own vocabulary rather than inventing a third state.</strong>
 * {@code RailUnreachable} is what this class returns, with the cooldown in its detail. From a caller's point of
 * view a rail it has stopped calling is indistinguishable from a rail that does not answer, and that is on
 * purpose: the consequences are identical - the transfer must not be marked failed, and it should be tried later
 * - so the caller needs no branch for "and also the breaker is open".
 *
 * <p><strong>The state is in memory, and that is a decision.</strong> A breaker is a memory of a recent bad spell
 * in <em>this</em> process's dealings with a rail; a restart is allowed to forget it, and a fresh process starting
 * closed is what lets an operator recover a deployment by restarting it. Persisting the state would make one
 * process's health a shared fact, and two instances of a worker failing together would then silence both - a
 * shared breaker across a fleet needs a shared clock and a shared threshold, which is a different design with its
 * own ADR rather than a table.
 *
 * <p><strong>The cooldown does not grow.</strong> An increasing cooldown is a second backoff wearing a breaker's
 * name: the per-transfer backoff already handles "this call keeps failing", and this class handles "the rail as a
 * whole is down". One probe per cooldown is enough for the second question.
 *
 * <p>Synchronized rather than lock-free, because the state is three fields that have to agree: a breaker's
 * counters are the definition of a critical section, and the volume here is one call per transfer per pass.
 */
public final class GuardedRail implements PaymentRail {

    private final PaymentRail rail;
    private final BreakerPolicy policy;
    private final Clock clock;

    private int consecutiveFailures;
    private boolean open;
    private Instant openUntil;

    /** Set while one call is in flight as a probe, so two threads cannot both test a rail after a cooldown. */
    private boolean probing;

    public GuardedRail(PaymentRail rail, BreakerPolicy policy, Clock clock) {
        this.rail = Objects.requireNonNull(rail, "rail");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String name() {
        return rail.name();
    }

    @Override
    public List<Currency> supportedCurrencies() {
        return rail.supportedCurrencies();
    }

    @Override
    public Result<RailReference> submit(TransferInstruction instruction) {
        Objects.requireNonNull(instruction, "instruction");

        return call(instruction.railRef(), () -> rail.submit(instruction));
    }

    @Override
    public Result<RailStatus> status(String railRef) {
        Objects.requireNonNull(railRef, "railRef");

        return call(railRef, () -> rail.status(railRef));
    }

    /**
     * One call through the breaker, or the refusal that stands in for it while the rail is left alone.
     *
     * <p>The permission check and the outcome recording are separate synchronized steps rather than one block
     * around the call: holding the lock across a network call would serialize every transfer behind the slowest
     * one, which is the opposite of what a worker with a batch of transfers is doing.
     */
    private <T> Result<T> call(String railRef, Supplier<Result<T>> attempt) {
        Instant leftAloneUntil = permission();

        if (leftAloneUntil != null) {
            return Result.fail(new RailUnreachable(railRef, "this rail has gone unanswered "
                    + policy.failuresBeforeOpening() + " times in a row, so it is being left alone until "
                    + leftAloneUntil + " (cooldown " + policy.cooldown() + ")"));
        }

        Result<T> answer;

        try {
            answer = attempt.get();
        } catch (RuntimeException thrown) {
            // An adapter that throws rather than answering is indistinguishable from one that did not answer, and
            // a breaker is the last place that should let an unclassified exception escape: the caller's contract
            // is a Result, so an exception here would change the caller's behaviour based on where the fault
            // happened. Counted as a failure, then answered in the port's own vocabulary.
            recordOutcome(false);
            return Result.fail(new RailUnreachable(railRef, "the rail's call threw instead of answering: "
                    + thrown.getClass().getSimpleName() + ": " + thrown.getMessage()));
        }

        recordOutcome(answered(answer));
        return answer;
    }

    /**
     * The instant the rail may be called again, or null when a call is allowed now.
     *
     * <p>Separate from {@link #recordOutcome} because a call has to be permitted, then made, then recorded - and
     * because a probe has to be claimed <em>before</em> the call rather than after it, or two threads arriving
     * together after a cooldown would both test the rail.
     */
    private synchronized Instant permission() {
        if (!open) {
            return null;
        }

        Instant now = clock.instant();

        if (now.isBefore(openUntil) || probing) {
            return openUntil;
        }

        probing = true;
        return null;
    }

    private synchronized void recordOutcome(boolean answered) {
        probing = false;

        if (answered) {
            // Anything the rail said counts as it being alive, including a refusal - which is a working rail with
            // an opinion, not an outage. A rail that only ever refuses keeps the breaker closed, and it should:
            // a group's insufficient funds are not the rail's availability.
            consecutiveFailures = 0;
            open = false;
            openUntil = null;
            return;
        }

        consecutiveFailures++;

        if (open || consecutiveFailures >= policy.failuresBeforeOpening()) {
            open = true;
            openUntil = clock.instant().plus(policy.cooldown());
        }
    }

    /**
     * Whether the rail answered, which is a question about the <em>shape</em> of the result rather than its
     * contents: a rejection is an answer, and so is a transfer that failed after being accepted. Only a
     * {@link RailUnreachable} means the rail did not answer.
     */
    private static boolean answered(Result<?> answer) {
        if (answer instanceof Result.Failure<?> failure) {
            return !(failure.error() instanceof RailUnreachable);
        }

        return true;
    }
}
