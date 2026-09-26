package io.reductxn.settlement.port.in;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long a transfer may sit at a rail without being confirmed before somebody has to look at it.
 *
 * <p><strong>The second question of this kind, and a separate policy from {@link Escalation} on purpose.</strong>
 * Escalation asks how long a rail may go <em>unanswered</em>; this asks how long a submission may go
 * <em>unconfirmed</em>. They are different failures with different evidence - a rail that says nothing versus a rail
 * that keeps saying "still pending" - and a deployment's tolerance for the two is not the same number: a rail that
 * has accepted the instruction is holding the group's money, so an hour of silence from it is a different thing from
 * an hour of it not picking up the phone. Section 4.4's fourth requirement ("a reconciliation sweep polls for
 * transfers stuck in {@code submitted} beyond a threshold") is this number; the polling it describes already happens
 * on every pass, and what was missing was the way to say which transfers had stopped being ordinary traffic.
 *
 * <p><strong>Measured from the acceptance, which is why {@code V510} stores it.</strong> The cheap version of this
 * policy is "escalate after N polls" - and it is the same mistake as counting tries for a silence (see
 * {@link Escalation}): with a driver window of two minutes, N polls means a duration that changes the moment the
 * window is retuned, and a threshold expressed in polls reads like nothing at all to whoever is on call. It is also
 * the mistake that caused the gap this policy closes: the retry counter resets whenever the rail answers, so every
 * poll that said "still pending" was evidence of health.
 *
 * <p>Kept separate from {@link Escalation} rather than folded into one policy with two numbers, and not merged into
 * a shared type either. Two numbers in one record would tie two unrelated deployments decisions together; one shared
 * type would put two beans of the same class in the context, which Spring resolves by parameter name - wiring that a
 * rename breaks at runtime instead of at compile time. So each question keeps its own record, its own javadoc and its
 * own property, and they share nothing but the arithmetic in {@link #cutOff(Instant)}.
 *
 * @param after how long a transfer may wait for a confirmation before it is somebody's problem. Zero is allowed and
 *              means "every transfer still at the rail when the pass looks", which is a defensible choice for a
 *              deployment whose rail confirms synchronously and whose reports should therefore be empty
 */
public record Reconciliation(Duration after) {

    public Reconciliation {
        Objects.requireNonNull(after, "after");

        if (after.isNegative()) {
            throw new IllegalArgumentException(
                    "a reconciliation threshold of " + after + " would report every transfer before a rail could "
                            + "have confirmed it, which is a report nobody can act on. Zero is allowed and means "
                            + "'everything still at the rail', which is a different statement");
        }
    }

    /**
     * The instant a transfer has to have been at the rail since, at or before, to be past this policy.
     *
     * <p>A cut-off rather than an answer, for the reason {@link Escalation#cutOff(Instant)} gives: the question is
     * asked of rows in a query, and "how stale is too stale" is configuration while the comparison belongs where the
     * rows are.
     */
    public Instant cutOff(Instant now) {
        Objects.requireNonNull(now, "now");

        return now.minus(after);
    }
}
