package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.List;

/**
 * What one rate refresh did, told in the terms an operator acts on.
 *
 * <p>A refresh that reported only "done" would be indistinguishable from one that found no currencies it knows,
 * or one whose provider has stopped pricing a currency this deployment transacts. So the report carries the two
 * sets that mean somebody has something to do: what the feed quoted and this deployment does not know, and what
 * this deployment knows and the feed did not quote. The second is the serious one - every obligation in that
 * currency will be refused with {@code fx.rate_unavailable} until a provider quotes it - and it is the reason
 * the catalogue has an {@code all} at all.
 *
 * @param provider           which feed answered
 * @param publishedAt        when the provider published what was ingested
 * @param pairsOffered       how many rates the publication implied, for this deployment's currencies
 * @param pairsWritten       how many were new; the rest were already recorded, which is what a second refresh
 *                           of the same publication looks like
 * @param unknownCurrencies  codes the feed quoted that this deployment's catalogue does not hold
 * @param unquotedCurrencies codes this deployment can transact that the feed quoted nowhere
 */
public record RateIngestion(
        String provider,
        Instant publishedAt,
        int pairsOffered,
        int pairsWritten,
        List<String> unknownCurrencies,
        List<String> unquotedCurrencies) {

    /** Whether this pass recorded anything, which is the difference between a new day and a repeat. */
    public boolean wroteAnything() {
        return pairsWritten > 0;
    }
}
