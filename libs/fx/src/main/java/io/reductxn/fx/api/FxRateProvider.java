package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;

/**
 * A source of exchange rates.
 *
 * <p>The port is owned here, in the pure core; adapters live in {@code apps/api/adapter/rate}
 * (RULE B19, recipe 10.8). Owning it here means {@code libs/fx} can be tested against a fixed rate
 * with no provider, no network and no container (P4).
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
