package io.reductxn.planning.api;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.netting.api.NettingResult;
import io.reductxn.netting.api.TransferInstruction;

import java.util.List;
import java.util.Objects;

/**
 * One currency's plan: everything that must happen in that currency, and nothing that happens in
 * another.
 *
 * <p>There is deliberately no cross-currency total anywhere in this type. Adding yen to rupees is not
 * addition, so a plan that reported one number would be reporting a meaningless one (ADR 0012).
 *
 * <p>Everything except {@link #currency()} and {@link #netting()} is derived from the netting result
 * rather than stored alongside it. A stored copy of the clearing position could disagree with the
 * positions it was supposedly copied from, and that disagreement would be invisible.
 *
 * @param currency the currency this partition settles in
 * @param netting  the unchanged outcome of handing this partition to the single-currency engine. Its
 *                 properties N1-N9 hold within this currency
 */
public record CurrencyPlan(Currency currency, NettingResult netting) {

    public CurrencyPlan {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(netting, "netting");
        if (!netting.net().currency().equals(currency)) {
            throw new CurrencyMismatchException(netting.net().currency(), currency);
        }
    }

    /**
     * The signed net position of the internal clearing account in this currency.
     *
     * <p>Zero in the ordinary case. Non-zero means the group promised somebody a currency it does not
     * hold, which is a real external requirement rather than a rounding artefact (ADR 0012 point 5).
     */
    public Money clearingPosition() {
        return netting.positions().stream()
                .filter(FxClearing::isClearing)
                .map(NetPosition::net)
                .findFirst()
                .orElseGet(() -> Money.zero(currency));
    }

    /**
     * What the group must obtain from outside, as a magnitude.
     *
     * <p>Reported per currency and never summed with another currency's requirement, because the
     * amounts are not commensurable.
     */
    public Money externalRequirement() {
        return clearingPosition().absolute();
    }

    /** Whether this currency cannot settle until the group funds it from outside (RULE S4). */
    public boolean requiresExternalFunding() {
        return !clearingPosition().isZero();
    }

    /** Member positions only, without the internal account. */
    public List<NetPosition> memberPositions() {
        return netting.positions().stream().filter(position -> !FxClearing.isClearing(position)).toList();
    }

    /** Transfers between two members: what a member reads as "who pays whom". */
    public List<TransferInstruction> memberTransfers() {
        return netting.transfers().stream().filter(transfer -> !touchesClearing(transfer)).toList();
    }

    /**
     * Transfers with the clearing account on one side.
     *
     * <p>Kept apart from {@link #memberTransfers()} on purpose. Presenting these as a member's debt
     * would show the group's FX exposure as though somebody in the group owed it to somebody else in
     * the group, which is exactly the confusion P2 makes it important to avoid.
     */
    public List<TransferInstruction> clearingTransfers() {
        return netting.transfers().stream().filter(CurrencyPlan::touchesClearing).toList();
    }

    private static boolean touchesClearing(TransferInstruction transfer) {
        return FxClearing.isClearing(transfer.fromUserId())
                || FxClearing.isClearing(transfer.toUserId());
    }

    @Override
    public String toString() {
        return currency.code() + ": " + netting.netTransferCount() + " transfers, tax "
                + netting.tax().format()
                + (requiresExternalFunding()
                        ? ", external requirement " + externalRequirement().format() : "");
    }
}
