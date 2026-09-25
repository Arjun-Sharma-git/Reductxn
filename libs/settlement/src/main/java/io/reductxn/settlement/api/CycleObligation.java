package io.reductxn.settlement.api;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.UUID;

/**
 * One commitment in a cycle, as the partitioner sees it.
 *
 * <p>Sealed, with exactly two cases. A cross-currency obligation is not "an obligation with an
 * optional quote" - that shape invites a null check to be forgotten somewhere, and the thing being
 * forgotten is which currency the creditor is paid in. Two cases force every caller to handle both,
 * and the partitioner's switch is exhaustive with no default branch to hide behind (ADR 0012 point 2).
 *
 * <p>Conversion has already happened by the time an obligation reaches here. The quote is carried
 * because it is the auditable record of what was promised and at what rate, not because anything left
 * to do depends on it.
 */
public sealed interface CycleObligation
        permits SingleCurrencyObligation, CrossCurrencyObligation {

    /** The member who owes. */
    UUID debtorId();

    /** The member who is owed. */
    UUID creditorId();

    /** What the debtor owes, in the currency the debt was incurred in. */
    Money owed();

    /** The currency the creditor is to be paid in. Equals {@link #owed()}'s currency when not cross. */
    Currency settlementCurrency();

    /** What the creditor receives. Equals {@link #owed()} when not cross-currency. */
    Money settlementAmount();
}
