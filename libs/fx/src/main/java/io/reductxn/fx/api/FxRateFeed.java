package io.reductxn.fx.api;

import java.util.Optional;

/**
 * A provider's published rates, as a source: what a loader asks for once per publication.
 *
 * <p><strong>Why this is a second port beside {@link FxRateProvider}.</strong> That one answers a question a
 * request asks - "what is the rate for this pair as of this instant" - and it is answered from {@code fx_rates},
 * which is a table. This one answers the question a *job* asks - "what did the provider publish most recently" -
 * and it is answered by a feed, which is a document. They fail differently, too: a request is refused
 * ({@code fx.rate_stale}, {@code fx.rate_unavailable}) while a job is retried, so folding the second into the
 * first would put a network call on the path of every conversion.
 *
 * <p><strong>What an implementation may not do.</strong> It may not invent a rate, carry a rate of one, or
 * rescue a failed fetch with a cached publication of unknown age. Those are the same three refusals
 * {@link FxRateProvider} states, for the same reason: a guessed rate propagates into real transfers and is an
 * accounting incident, where a missing one is only an availability incident (ADR 0013, point 6).
 *
 * <p><strong>Empty is not the same as broken.</strong> Empty means the provider has published nothing this feed
 * can read - a deployment whose feed file has not been dropped yet, a provider that has not published today -
 * and a loader does nothing with it. A feed that cannot be reached or cannot be read throws, because that is a
 * fault about the deployment rather than a fact about the market, and turning it into an empty publication
 * would make a provider outage look like a quiet day.
 *
 * <p>Owned here, implemented in {@code apps/api} - the module that already implements the reading provider,
 * because it is the one that stores a rate's quotes and reads the rate's readers. Unlike that adapter this one
 * reads no table, so it lives in {@code adapter/feed} rather than {@code adapter/persistence}: RULE B16 pins
 * jOOQ to one package, and pinning a document-fetching adapter there would be a rule applied to a case it was
 * not written for.
 */
public interface FxRateFeed {

    /**
     * The most recent publication this provider has made.
     *
     * @return the publication, or empty when the provider has published nothing readable yet
     * @throws RuntimeException when the feed cannot be obtained or read; the caller is a job, which reports it
     *                          and tries again rather than failing a settlement over it
     */
    Optional<PublishedRates> latest();

    /** Stable feed identifier, for the log line and for the summary a refresh reports. */
    String name();
}
