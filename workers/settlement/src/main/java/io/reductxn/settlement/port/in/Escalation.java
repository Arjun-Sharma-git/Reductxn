package io.reductxn.settlement.port.in;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long a rail may go unanswered before somebody has to look at it.
 *
 * <p><strong>This is the policy section 4.4's "or escalate" was waiting for, and it is a judgement rather than a
 * mechanism.</strong> The retry machinery already decides how often to ask a silent rail again
 * ({@link Backoff}) and the driver already polls it for ever; what was missing is the answer to "for ever is a
 * long time, and at what point does this stop being the worker's patience and start being a person's problem".
 * There is no right value: it depends on what the rail settles, how bad a delayed settlement is for the group
 * that is waiting, and who is on call. So it is configuration, and the default is stated rather than implied.
 *
 * <p><strong>Measured as a duration, against a stored instant.</strong> The tempting cheaper policy is a count of
 * tries ("escalate after eight unanswered calls"), which needs no new column - and it is wrong for a reason worth
 * writing down: with an exponential backoff the same count means a different amount of time the moment
 * {@code retry.base} or {@code retry.cap} is retuned, so a deployment change would silently move the escalation
 * line by hours. Patience is a statement about time, so it is measured from the instant the silence began
 * ({@code V508}).
 *
 * <p>A pure function of an instant, with no clock of its own: the caller passes "now", which is what makes the
 * policy testable without waiting two hours and makes the same instant always produce the same cut-off.
 *
 * <p><strong>Its companion is {@link Reconciliation}, and they are deliberately two records rather than one.</strong>
 * That policy answers the other way a transfer gets stuck at a rail - accepted and never confirmed, with no silence
 * to measure because the rail keeps answering "still pending". A deployment's tolerance for the two is two numbers
 * and wants two names; folding them into one record with two durations would tie two unrelated decisions together,
 * and sharing one type would put two beans of the same class in the context (ADR 0026).
 *
 * @param after how long a transfer may go unanswered before it is somebody's problem. Zero is allowed and means
 *              "every unanswered call", which is a defensible deployment choice for a rail nobody can afford to
 *              have silent for even a minute
 */
public record Escalation(Duration after) {

    public Escalation {
        Objects.requireNonNull(after, "after");

        if (after.isNegative()) {
            throw new IllegalArgumentException(
                    "patience of " + after + " would escalate every silence before it began, including silences "
                            + "that are over. The point of the policy is to escalate what is still happening");
        }
    }

    /**
     * The instant a transfer's silence has to have begun at or before to be past this policy.
     *
     * <p>Returns a cut-off rather than answering "is this transfer stuck", because the question is asked of rows in
     * a query: the same shape as the driver's window, where how stale an attempt must be is configuration and the
     * comparison happens where the rows are (RULE B17 - the adapter reads them, and {@link StuckTransfers} is built
     * from what it found).
     */
    public Instant cutOff(Instant now) {
        Objects.requireNonNull(now, "now");

        return now.minus(after);
    }
}
