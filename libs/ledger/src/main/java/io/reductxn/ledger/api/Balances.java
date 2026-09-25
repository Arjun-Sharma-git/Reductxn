package io.reductxn.ledger.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Balances, computed from postings (invariant L5: a balance is a projection, never a stored truth).
 *
 * <p>There is no balance type in this module and no balance column. What exists is this: a pure function
 * from postings to a position. Every caller that wants a figure recomputes it, which is why a balance
 * cannot drift from the postings it came from - there is nothing to drift.
 *
 * <p>Reports a <strong>net debit</strong>, and deliberately stops there. What a net debit means depends
 * on the account's type: a debit increases an asset and decreases a liability, and account typing lives
 * in the accounts table, not in this projection. Naming the result a "balance" before knowing the type
 * would hide a sign convention inside every caller.
 *
 * <p>Used to measure the two invariants that need a position. L8 asks whether {@code fx_clearing} is
 * zero per currency at the end of a cycle, and the answer is
 * {@code netDebitOf(clearingAccountId, currency, postings)} compared to zero - the clearing account id
 * belongs to {@code libs/planning}, which this module may not see (RULE B2), so it is a parameter here
 * rather than a constant. L7's accounting half is the same call on the same account.
 */
public final class Balances {

    private Balances() {
    }

    /**
     * The net debit position of one account in one currency: debits minus credits.
     *
     * <p>Zero for an account with no postings, rather than an error. An account that was never used has a
     * position of zero, and requiring the caller to check for absence first would make the common case
     * awkward without making anything safer.
     *
     * <p>Every posting is checked against {@code currency}, including ones for other accounts. A mixed
     * collection is a caller mistake, and accepting it would silently sum across currencies using one
     * currency's exponent - the arithmetic this module exists to make impossible.
     */
    public static Money netDebitOf(UUID accountId, Currency currency, Collection<Posting> postings) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(postings, "postings");

        Money net = Money.zero(currency);
        for (Posting posting : postings) {
            if (!currency.equals(posting.currency())) {
                throw new CurrencyMismatchException(currency, posting.currency());
            }
            if (accountId.equals(posting.accountId())) {
                net = net.plus(posting.signedAmount());
            }
        }
        return net;
    }

    /**
     * Every posting held in one currency, across a set of entries.
     *
     * <p>Exists so {@link #netDebitOf} can be pointed at a cycle's entries without every caller writing
     * the same filter. L6 guarantees a posting is in its entry's currency, so filtering entries by
     * currency is exactly filtering their postings.
     */
    public static List<Posting> postingsIn(Collection<JournalEntry> entries, Currency currency) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(currency, "currency");

        List<Posting> postings = new ArrayList<>();
        for (JournalEntry entry : entries) {
            if (currency.equals(entry.currency())) {
                postings.addAll(entry.postings());
            }
        }
        return postings;
    }
}
