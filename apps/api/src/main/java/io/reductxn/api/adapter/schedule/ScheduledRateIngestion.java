package io.reductxn.api.adapter.schedule;

import io.reductxn.api.port.in.IngestRates;
import io.reductxn.api.port.in.RateIngestion;
import io.reductxn.fx.api.FxRateFeed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * The cadence that keeps {@code fx_rates} fed: what makes the cross-currency write path work in a deployment.
 *
 * <p>Until this existed, the tables were there and nothing wrote them, so every cross-currency obligation was
 * answered {@code fx.rate_unavailable} - correct fail-closed behaviour and useless in production. The rate the
 * provider published had no way into the database, and no request could have fetched it: a conversion that
 * waited for a network call to a market-data provider would make every obligation's latency a vendor's, and its
 * availability the vendor's too.
 *
 * <p><strong>The bean exists only when a feed is configured</strong> ({@code reductxn.rates.feed}). That is the
 * difference from the settlement worker's driver, which is on by default and can be switched off: rate ingestion
 * without a configured provider has nothing to do, whereas a worker with no driver settles nothing. So this is
 * silent and absent rather than silently idle, and a deployment that has not chosen a provider still starts,
 * ingests nothing and refuses cross-currency work loudly.
 *
 * <p><strong>A fault is logged and swallowed, like the settlement driver's.</strong> {@code @Scheduled} surfaces
 * a fault by ending the schedule, and a refresh that stopped after one unreachable fetch would leave the
 * deployment with no new rates until somebody restarted it. A missing file, an unreachable host and a document
 * this code cannot read are all reported at warn and tried again next tick - and nothing is written on any of
 * those paths, because half a publication is worse than none ({@code IngestRates} says why).
 */
@Component
@ConditionalOnProperty(name = "reductxn.rates.feed")
public class ScheduledRateIngestion {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRateIngestion.class);

    private final IngestRates ingestRates;
    private final FxRateFeed feed;
    private final Duration refresh;

    public ScheduledRateIngestion(
            IngestRates ingestRates,
            FxRateFeed feed,
            @Value("${reductxn.rates.refresh:PT6H}") Duration refresh) {

        this.ingestRates = ingestRates;
        this.feed = feed;
        this.refresh = refresh;
    }

    /**
     * Asks the feed whose rates have been recorded already, and says what is new.
     *
     * <p>The schedule is a setting ({@code reductxn.rates.refresh}, six hours by default) rather than policy:
     * how often to ask is a load decision, and the freshness bound the rates themselves carry
     * ({@code reductxn.rates.max-age}) is what decides whether one may still be locked. An interval shorter than
     * the provider's own publication day is deliberate - a deployment should read a new day on the day it is
     * published, and a longer interval would mean a rate published on Friday afternoon was still unread on
     * Monday. Asking again costs one request and writes nothing, because a publication already recorded
     * conflicts with itself.
     *
     * <p>The first pass is soon after startup rather than one interval later, and that is the opposite of the
     * settlement driver's choice on purpose. A worker waiting an interval before offering money movements to a
     * rail loses a minute; a deployment waiting six hours before its first rate refuses every cross-currency
     * obligation it receives in that time, over a document it could have read in seconds. Thirty seconds is long
     * enough for the context to finish starting ({@code reductxn.rates.refresh-initial}).
     */
    @Scheduled(
            initialDelayString = "${reductxn.rates.refresh-initial:PT30S}",
            fixedDelayString = "${reductxn.rates.refresh:PT6H}")
    public void refresh() {
        try {
            Optional<RateIngestion> ingestion = ingestRates.ingest(feed);

            ingestion.ifPresentOrElse(this::report, () -> log.info(
                    "the rate feed {} published nothing to read; every rate already stored still stands",
                    feed.name()));

        } catch (RuntimeException fault) {
            log.warn("the rate refresh from {} failed and wrote nothing. Rates already stored are unaffected, and "
                            + "the refresh is tried again in {}: {}",
                    feed.name(), refresh, fault.getMessage(), fault);
        }
    }

    /**
     * What the pass found, with the two lines an operator has to be able to act on.
     *
     * <p>A currency the catalogue holds and the feed does not quote is a warning rather than an information
     * line: every obligation in it will be refused with no rate, and that is a decision somebody has to make -
     * add a provider for it, or stop transacting in it.
     */
    private void report(RateIngestion ingestion) {
        log.info("rate refresh from {}: published {}, {} pair(s) for this deployment's currencies, {} new. "
                        + "The feed quotes {} currencies this deployment does not know",
                ingestion.provider(), ingestion.publishedAt(), ingestion.pairsOffered(),
                ingestion.pairsWritten(), ingestion.unknownCurrencies().size());

        if (!ingestion.unquotedCurrencies().isEmpty()) {
            log.warn("{} is not quoted by {} at all, so no rate can be derived for it and every obligation in it "
                            + "will be refused: {}",
                    ingestion.unquotedCurrencies().size() == 1 ? "one currency" : "these currencies",
                    ingestion.provider(), ingestion.unquotedCurrencies());
        }
    }
}
