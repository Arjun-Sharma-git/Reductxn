package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One transfer no rail has decided, and how long nobody has heard from that rail.
 *
 * <p>Everything here is a row: the transfer, the run it belongs to (and whose book that is), the last thing the
 * rail said, how many calls in a row went unanswered, and when that spell of silence began. Nothing is derived,
 * which is what makes it usable as the answer to "what is stuck" without a second implementation of the retry
 * arithmetic - and {@link #silentFor} is the subtraction a reader would otherwise do on paper.
 *
 * <p><strong>{@code status} is not decoration.</strong> It is the difference between two very different problems:
 * {@code submitted} means the rail accepted the instruction and then went quiet, so the money may have moved and
 * the answer is worth chasing; {@code authorized} means the rail never took it, so nothing has happened yet. An
 * escalation report that only said "unanswered" would hide the case an operator most needs to see.
 *
 * @param runId            the run whose plan this transfer is part of
 * @param groupId          whose book the run is, so a report can be read without a second lookup
 * @param transferId       the transfer
 * @param railRef          RULE S2's idempotency key, which is also what a rail's support desk will recognise
 * @param currency         the currency it moves
 * @param amount           the amount, exact in minor units
 * @param status           where the transfer stands: {@code submitted} means the rail has it and went quiet
 * @param railDetail       the last thing the rail said, when it said anything - usually the reason a call failed
 * @param unansweredAttempts how many calls in a row have gone unanswered
 * @param unansweredSince  when that spell began, which is what an escalation policy measured
 */
public record SilentTransfer(
        UUID runId,
        UUID groupId,
        UUID transferId,
        String railRef,
        Currency currency,
        Money amount,
        TransferStatus status,
        String railDetail,
        int unansweredAttempts,
        Instant unansweredSince) {

    public SilentTransfer {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(unansweredSince, "unansweredSince");

        if (status.isResolved()) {
            throw new IllegalArgumentException(
                    "transfer " + transferId + " is " + status.wireName() + " and reported as one no rail has "
                            + "decided. A resolved transfer has been decided by a rail that answered, so this is "
                            + "either another transfer or a stale reading");
        }
    }

    /** How long the rail has been silent, as of {@code now}. */
    public Duration silentFor(Instant now) {
        Objects.requireNonNull(now, "now");
        return Duration.between(unansweredSince, now);
    }
}
