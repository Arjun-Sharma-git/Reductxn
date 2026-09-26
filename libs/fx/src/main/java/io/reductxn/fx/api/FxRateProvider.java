package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;

/**
 * A source of exchange rates.
 *
 * <p>The port is owned here, in the pure core; an adapter lives in {@code apps/api}, in its
 * {@code adapter/persistence} package. Owning it here means {@code libs/fx} can be tested against a fixed
 * rate with no provider, no network and no container (P4).
 *
 * <p><strong>Why the adapter's package is named rather than left open.</strong> This comment used to say
 * {@code apps/api/adapter/rate}, and RULE B16 - which keeps every jOOQ type inside one package so no row type
 * escapes - makes that impossible for an adapter that reads a table. The rule is mechanical and enforced; the
 * sentence was prose written before the rule's scope was known, so the sentence was corrected. A provider that
 * fetched rates over the network instead of from the database could live anywhere in the module, and the day one
 * exists this sentence should say so.
 *
 * <p><strong>That day arrived (ADR 0027), and there are now two things behind this question.</strong> This port is
 * answered from the database by {@code JooqFxRateProvider} in {@code apps/api/adapter/persistence} - a table read,
 * which RULE B16 pins there. The fetching is a *second* port, {@link FxRateFeed}, answered by
 * {@code EcbReferenceRateFeed} in {@code apps/api/adapter/feed}: it reads no table, so it is not in the persistence
 * package, and it is a job's question ("what has the provider published") rather than a request's. A rate that
 * arrives from outside the process has to be written somewhere before it is read, and that is the split.
 *
 * <p>Keep this deliberate and small. An adapter for a market data provider does not belong in the
 * module that also decides how many minor units a creditor is owed.
 */
public interface FxRateProvider {

    /**
     * The most recent usable rate for the pair as of the given instant.
     *
     * <p>Implementations must **not** return a placeholder, a rate of one, or a cached value of
     * unknown age in place of failing. A guessed rate propagates into real transfers and is an
     * accounting incident, whereas an exception is only an availability incident (ADR 0013, point 6).
     *
     * @throws UnavailableRateException when no rate can be obtained at all
     */
    FxRate rateFor(Currency base, Currency quote, Instant asOf);

    /** Stable provider identifier, recorded on every rate so an audit can say where it came from. */
    String name();
}
