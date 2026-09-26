package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.Money;

import java.util.Objects;

/**
 * One currency's outcome in a run: what the plan says about it, and what it needs from outside.
 *
 * <p>The four figures are `libs/netting`'s answers about this partition - gross, net, tax and tax saved -
 * stored with the run because they are what the group is asked to approve. They are not re-derived from the
 * transfers: doing that would mean running the engine again to read a run, which is what storing the plan
 * avoids.
 *
 * <p>Member transfers and clearing transfers are counted separately on purpose. A transfer touching
 * {@code fx_clearing} is part of the group's external requirement, not a member's debt, and reporting the two
 * together would show the group's FX exposure as though somebody in the group owed it to somebody else in the
 * group (ADR 0012 point 6). {@code memberTransferTotal} sums the first kind only, for the same reason: it is
 * what "who pays whom" adds up to, and it must not include the group's own currency gap.
 *
 * <p>No total across currencies, ever. Adding yen to rupees is not addition (ADR 0012 point 4), so there is no
 * field here that could be summed by accident.
 *
 * @param currency              the partition this describes
 * @param gross                 every obligation's amount, before netting
 * @param net                   what actually has to move after netting
 * @param tax                   tax assessed on this partition
 * @param taxSaved              what netting saved against settling every obligation directly
 * @param clearingPosition      the signed position of the clearing account in this currency; zero in the
 *                              ordinary case, non-zero when the group promised a currency it does not hold
 * @param externalRequirement   the magnitude of {@code clearingPosition}: an exact amount per currency, never
 *                              summed with another currency's requirement
 * @param memberTransferTotal   the sum of the transfers between two members
 * @param memberTransferCount   how many of those there are
 * @param clearingTransferCount how many transfers have the clearing account on one side
 */
public record PartitionOutcome(
        Currency currency,
        Money gross,
        Money net,
        Money tax,
        Money taxSaved,
        Money clearingPosition,
        Money externalRequirement,
        Money memberTransferTotal,
        int memberTransferCount,
        int clearingTransferCount) {

    public PartitionOutcome {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(taxSaved, "taxSaved");
        Objects.requireNonNull(clearingPosition, "clearingPosition");
        Objects.requireNonNull(externalRequirement, "externalRequirement");
        Objects.requireNonNull(memberTransferTotal, "memberTransferTotal");

        // Every amount is in this partition's currency, because a partition is what makes per-currency
        // zero-sum meaningful (N1) and a stray currency here would put two currencies in one total.
        for (Money amount : new Money[] {gross, net, tax, taxSaved, clearingPosition, externalRequirement,
                memberTransferTotal}) {
            if (!amount.currency().equals(currency)) {
                throw new CurrencyMismatchException(amount.currency(), currency);
            }
        }

        if (!clearingPosition.absolute().equals(externalRequirement)) {
            throw new IllegalArgumentException(
                    "the external requirement for " + currency.code() + " is " + externalRequirement
                            + ", which is not the magnitude of the clearing position " + clearingPosition
                            + ". Reporting the wrong one tells a group to fund an amount it does not owe");
        }

        if (memberTransferCount < 0 || clearingTransferCount < 0) {
            throw new IllegalArgumentException(
                    "transfer counts cannot be negative: " + memberTransferCount + " member and "
                            + clearingTransferCount + " clearing transfers");
        }
    }

    /** Whether this currency cannot settle until the group funds it from outside (RULE S4). */
    public boolean requiresExternalFunding() {
        return !clearingPosition.isZero();
    }
}
