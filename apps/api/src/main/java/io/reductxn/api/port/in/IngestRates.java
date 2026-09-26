package io.reductxn.api.port.in;

import io.reductxn.fx.api.FxRateFeed;

import java.util.Optional;

/**
 * One pass of rate ingestion: what the provider published, turned into the rates this deployment can transact.
 *
 * <p><strong>The feed is an argument, not a dependency.</strong> A deployment configures a provider or it does
 * not, and one that has configured none must still start - so the use case is not wired to a feed, and the thing
 * that is ({@code ScheduledRateIngestion}) only exists when there is one. It also makes the pass testable
 * without a network, a file or a container: a feed is whatever the caller passes.
 *
 * <p><strong>What it does not decide.</strong> Which pairs a deployment needs is the currency catalogue's answer
 * (the currencies it knows, intersected with the ones the provider quotes) and what a pair is worth is
 * {@code libs/fx}'s. This use case is the sequence: ask, intersect, derive, store, report.
 */
public interface IngestRates {

    /**
     * Asks the feed for its latest publication and stores the rates that are new.
     *
     * @return a report of the pass, or empty when the provider has published nothing this feed can read - which is
     *         not a failure and which a caller should not report as one
     * @throws IllegalStateException when the feed cannot be obtained; a caller that is a job reports it and tries
     *                               again, and one that is a request would be wrong to exist
     * @throws io.reductxn.fx.api.InvalidRateException when the publication itself is not usable - a rate out of
     *                                                 range, or a currency it does not quote - because storing part
     *                                                 of a day's market data would leave a deployment half-priced
     */
    Optional<RateIngestion> ingest(FxRateFeed feed);
}
