package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * Currency arriving from outside to cover a run's external requirement (RULE S4).
 *
 * <p><strong>This records a fact that happened elsewhere, and that is the whole point.</strong> reductxn is
 * non-custodial (P2): it holds no CBDC and no keys, so it cannot make the money arrive and must not pretend it
 * did. Something outside - a member's bank, a treasurer moving the group's own balance, a rail that performs
 * FX - moves the currency, and this is how the record of that movement reaches the run. What the worker owns is
 * the gate: whether the requirement is covered, and therefore whether the run may execute.
 *
 * <p>Section 7 of the spec lists "how a group funds an external FX requirement operationally, and who is
 * permitted to do it" as open. This request deliberately says nothing about either: it takes who and why as
 * opaque strings so that the answer can change without changing the run's schema. The permission question is
 * an authorization concern and belongs to whatever surface accepts this call, not to the run.
 *
 * @param runId      the run whose requirement is being covered
 * @param currency   the currency brought in. Must be one the run actually needs, or the funding is refused
 *                   rather than recorded - a funding nothing was waiting for is a row that says a gap was
 *                   closed when a different one was
 * @param amount     how much arrived, exact, in minor units, strictly positive
 * @param fundingRef the caller's reference for this funding. It is RULE S2's idempotency key for the funding
 *                   path: the same reference twice records nothing the second time, so a retried call after a
 *                   timeout cannot double-count what a group brought in. An external transfer id is the
 *                   natural value - it is then both the key and the audit trail
 */
public record FundingRequest(UUID runId, Currency currency, Money amount, String fundingRef) {

    public FundingRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(fundingRef, "fundingRef");

        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "the funding is for " + currency.code() + " but the amount is in "
                            + amount.currency().code() + ". A funding covers one currency");
        }
        if (fundingRef.isBlank()) {
            throw new IllegalArgumentException(
                    "a funding needs a reference, or a retry cannot be told from a second transfer of the same "
                            + "amount (RULE S2)");
        }
    }
}
