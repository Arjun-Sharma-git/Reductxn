package io.reductxn.api;

import io.reductxn.api.adapter.feed.EcbFeedDocument;
import io.reductxn.api.adapter.feed.EcbReferenceRateFeed;
import io.reductxn.api.adapter.schedule.ScheduledRateIngestion;
import io.reductxn.fx.api.FxRateFeed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment that has configured a provider: the half the other tests cannot see.
 *
 * <p>Every other test in this module constructs the feed itself, which is what makes them independent of the
 * property that turns ingestion on - and it means none of them would notice if the conditional bean, the property
 * name or the job's wiring were wrong. A deployment would notice, by ingesting nothing for ever while its log said
 * nothing at all, so this test boots a context with {@code reductxn.rates.feed} set.
 *
 * <p>The schedule is pushed an hour out deliberately: the point is to run one pass by hand, not to race a
 * background tick that would ingest the same six rates at the same time as the assertion about them.
 */
@SpringBootTest(properties = {
        "reductxn.rates.refresh-initial=PT1H",
        "reductxn.rates.refresh=PT12H" })
class RateFeedWiringTest extends DatabaseTest {

    /** The provider's document, in a file the context is pointed at before it starts. */
    private static final Path FEED = writeTheDocument();

    @DynamicPropertySource
    static void pointTheDeploymentAtTheFeed(DynamicPropertyRegistry registry) {
        registry.add("reductxn.rates.feed", FEED::toString);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ScheduledRateIngestion job;

    @Test
    void aConfiguredProviderGivesTheDeploymentAFeedAndAJob() {
        assertThat(context.getBean(FxRateFeed.class))
                .as("the feed adapter is conditional on the property, and this is what proves the name matches")
                .isInstanceOf(EcbReferenceRateFeed.class);
        assertThat(context.getBeanNamesForType(ScheduledRateIngestion.class))
                .as("and the job exists, so a deployment that names a provider gets one that runs")
                .hasSize(1);
    }

    @Test
    void aRefreshThroughTheConfiguredWiringStoresTheRatesThisDeploymentCanTransact() {
        job.refresh();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM fx_rates", Integer.class))
                .as("the property reached the adapter, the adapter parsed the document this test wrote, the "
                        + "derivation ran and the store wrote - which is the whole path a deployment takes and "
                        + "the path no other test in this module exercises through Spring")
                .isEqualTo(6);
    }

    private static Path writeTheDocument() {
        try {
            Path file = Files.createTempFile("eurofxref-daily", ".xml");
            Files.writeString(file, EcbFeedDocument.DAILY);

            return file;
        } catch (IOException fault) {
            throw new UncheckedIOException(fault);
        }
    }
}
