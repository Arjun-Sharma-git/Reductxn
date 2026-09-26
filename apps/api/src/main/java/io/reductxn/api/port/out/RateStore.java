package io.reductxn.api.port.out;

import io.reductxn.fx.api.FxRate;

import java.util.List;

/**
 * Rates, written into {@code fx_rates} - {@code libs/fx}'s table, reached through an adapter (RULE B17).
 *
 * <p><strong>Idempotent by the database, not by this port's caller.</strong> {@code V403} makes a provider's
 * publication unique on {@code (pair, source, instant)}, so a refresh that runs twice, overlaps itself or is
 * repeated by hand writes each rate once. The caller therefore does not have to know whether it has been here
 * before, which is the property a scheduled job needs: it has no memory of the last run, and giving it one
 * would be a second record of what the table already records.
 *
 * <p>What it deliberately is not: a reader, a validator or an updater. A rate arrives built and validated
 * ({@code FxRate} refuses to exist malformed), nothing is ever corrected in place - a provider that publishes a
 * correction publishes a second rate at a later instant, which is what superseding means here - and the reader
 * is {@code FxRateProvider}, which is a different question asked by a different caller.
 */
public interface RateStore {

    /**
     * Stores every rate that is not already stored.
     *
     * @return how many rows were written, which is how a refresh tells "the provider published a new day" from
     *         "we have already read this one"
     */
    int record(List<FxRate> rates);
}
