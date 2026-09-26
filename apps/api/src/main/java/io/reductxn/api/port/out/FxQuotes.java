package io.reductxn.api.port.out;

import io.reductxn.fx.api.FxQuote;

/**
 * Storing the quote a cross-currency obligation was locked at.
 *
 * <p><strong>This port exists because a quote is history.</strong> `FxQuote`'s own documentation calls
 * immutability the point: the settlement rate is what the creditor was promised, so it is never restated. The
 * obligation points at this row rather than carrying a copy of the conversion, and the quote embeds the rate
 * it used rather than pointing at `fx_rates`, so reading a past obligation needs nothing but the obligation
 * and its quote (ADR 0013 point 5).
 *
 * <p><strong>Written before the entries are appended, in the same transaction.</strong> A quote with no
 * entries is a conversion nothing recorded; entries with no quote are worse, because the pair's amounts could
 * never be explained. Both are impossible if the write order is this one and the transaction is the use case's,
 * which is why the signature returns nothing and the adapter does not open a transaction of its own.
 *
 * <p>Not part of `libs/fx`. That module computes conversions and hands them over; it does not store anything -
 * see its README's "what this module does NOT do". This is the repository the module that records obligations
 * needs, and it lives where the other repositories of `apps/api` do.
 */
public interface FxQuotes {

    /**
     * Writes the quote.
     *
     * <p>The caller has already decided the id, because the quote belongs to a commitment and two places
     * generating identity for one commitment is two identities.
     */
    void record(FxQuote quote);
}
