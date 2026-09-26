package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One transfer no rail has decided, why nobody is waiting for it any more, and how long that has been true.
 *
 * <p><strong>Why "stuck" rather than "silent".</strong> This record used to be {@code SilentTransfer}, and the name
 * was only true of half the transfers the report needs to carry. A rail that answers nothing leaves a silence, which
 * {@code V508} measures; a rail that keeps answering "still pending" leaves no silence at all - it resets the retry
 * counter on every poll - and can hold a movement for a week while every measure this module had said the transfer
 * was healthy. Both are transfers no rail has decided, so both belong in the one report a person reads, and a type
 * whose name promised silence could not honestly carry the second kind (ADR 0026).
 *
 * <p><strong>{@link Reason} is the difference between two problems an operator acts on differently.</strong>
 * {@code NO_CONFIRMATION} means the rail has the instruction and will not confirm it: the money may have moved, the
 * rail's own reference exists, and the person to chase is the rail's. {@code NO_ANSWER} means the rail never took it
 * and has stopped answering: nothing has happened yet, and the question is whether the instruction arrived at all.
 * Those are different phone calls, which is why the reason is a value here rather than something a reader infers
 * from a status it has to know how to read.
 *
 * <p><strong>Everything else is a row.</strong> The transfer, the run it belongs to (and whose book that is), the
 * last thing the rail said, how many calls in a row went unanswered, when the rail took the instruction and when
 * the current silence began. Nothing is derived except {@link #reason()}, {@link #stuckSince()} and
 * {@link #stuckFor(Instant)} - the three statements about those rows that a reader would otherwise make on paper,
 * and the two that decide which of the report's lines the transfer answers to.
 *
 * <p><strong>A submission instant is kept as history and is not a claim about the present.</strong> A transfer
 * confirmed from the rail keeps the instant it was accepted, because that is when the wait began; the report simply
 * does not carry resolved transfers. A transfer the rail confirmed on the first call was never in flight, so it has
 * none - which is what makes a null here mean "no rail ever took this instruction" (see {@code V510}).
 *
 * @param runId              the run whose plan this transfer is part of
 * @param groupId            whose book the run is, so a report can be read without a second lookup
 * @param transferId         the transfer
 * @param railRef            RULE S2's idempotency key, which is also what a rail's support desk will recognise
 * @param currency           the currency it moves
 * @param amount             the amount, exact in minor units
 * @param status             where the transfer stands: {@code submitted} means the rail has it, {@code authorized}
 *                           means the rail never took it
 * @param railDetail         the last thing the rail said, when it said anything - usually the reason a call failed,
 *                           or the rail's own note on the submission
 * @param unansweredAttempts how many calls in a row have gone unanswered. Zero on a transfer the rail is still
 *                           answering about, which is exactly the case a silence-based report could not see
 * @param submittedAt        when the rail accepted the instruction, or null when no rail ever did
 * @param unansweredSince    when the current spell of silence began, or null when the rail has been answering
 */
public record StuckTransfer(
        UUID runId,
        UUID groupId,
        UUID transferId,
        String railRef,
        Currency currency,
        Money amount,
        TransferStatus status,
        String railDetail,
        int unansweredAttempts,
        Instant submittedAt,
        Instant unansweredSince) {

    /** Why no rail has decided this transfer, which is the question an operator starts with. */
    public enum Reason {

        /**
         * The rail has the instruction and has not confirmed it: the money may have moved.
         *
         * <p>Reported when the transfer is at the rail ({@code submitted}), whether it got there by an accepted call
         * or by a poll that said so. It is the more informative of the two reasons when both are true - a rail that
         * took the money and has now also gone quiet is still a rail holding a movement.
         */
        NO_CONFIRMATION,

        /**
         * The rail never took the instruction and is not answering: nothing has moved.
         *
         * <p>The silence half of the report, and the half section 4.4 described: an {@code authorized} transfer has
         * been offered and was not received, which is a different problem with a different remedy from a rail holding
         * money it will not confirm.
         */
        NO_ANSWER;

        /** The wire and log form: lower case, as the report writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public StuckTransfer {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");

        if (!status.isOffered()) {
            throw new IllegalArgumentException(
                    "transfer " + transferId + " is " + status.wireName() + " and reported as one no rail has "
                            + "decided. A transfer is only ever stuck if a rail was asked to move it, and an "
                            + "answered or unsubmitted transfer is neither - this is either another transfer or a "
                            + "stale reading");
        }

        if (submittedAt == null && unansweredSince == null) {
            throw new IllegalArgumentException(
                    "transfer " + transferId + " is reported as stuck with no instant to say since when: no rail "
                            + "accepted it and no silence began. The report exists to say how long somebody has had "
                            + "to look at this, so a row with no answer to that question is not one it can carry");
        }

        if (status == TransferStatus.AUTHORIZED && (submittedAt != null || unansweredSince == null)) {
            throw new IllegalArgumentException(
                    "transfer " + transferId + " is authorized - the rail never took it - and reports a submission "
                            + "instant of " + submittedAt + " and a silence of " + unansweredSince + ". A transfer "
                            + "the rail never accepted cannot carry an acceptance, and the only thing that makes such "
                            + "a transfer stuck is a rail that has stopped answering: this row says neither");
        }
    }

    /**
     * Why this transfer needs a person.
     *
     * <p>Read from the transfer's own state rather than from which threshold it crossed, because the two agree in
     * every case the report can produce: a transfer at the rail has an unconfirmed movement, and one that never got
     * there has an unanswered call. Where they could disagree - a transfer at the rail whose acceptance instant
     * predates {@code V510}, so its age comes from its silence instead - the state is the better answer, because it
     * is the one that says what the rail is holding.
     */
    public Reason reason() {
        return status.isAtTheRail() ? Reason.NO_CONFIRMATION : Reason.NO_ANSWER;
    }

    /**
     * When the trouble this report is about began.
     *
     * <p>The acceptance, when a rail took the instruction; otherwise the start of the silence. This is the only thing
     * the report measures against a policy, and it is deliberately not "the most recent poll": an interval that
     * restarts whenever the rail answers is exactly the measure that hid these transfers.
     *
     * <p>For a transfer at the rail whose acceptance instant is unknown, this is the silence's start, which
     * understates the wait rather than overstating it - the honest direction for a number that tells somebody how
     * long they had to act on it.
     */
    public Instant stuckSince() {
        return submittedAt != null ? submittedAt : unansweredSince;
    }

    /** How long this transfer has been stuck, as of {@code now}. */
    public Duration stuckFor(Instant now) {
        Objects.requireNonNull(now, "now");

        return Duration.between(stuckSince(), now);
    }
}
