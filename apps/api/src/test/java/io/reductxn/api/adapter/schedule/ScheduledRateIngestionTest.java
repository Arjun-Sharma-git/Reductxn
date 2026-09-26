package io.reductxn.api.adapter.schedule;

import io.reductxn.api.port.in.IngestRates;
import io.reductxn.api.port.in.RateIngestion;
import io.reductxn.fx.api.FxRateFeed;
import io.reductxn.fx.api.PublishedRates;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The rate refresh, without a database and without a scheduler.
 *
 * <p>At this seam rather than through a Spring context, for the reason {@code ScheduledRunDriverTest} gives: the
 * decisions are here, and a context would prove only that the annotation is registered. What is worth checking is
 * what the job does with each of the three answers a pass can give it - a report, nothing, and a fault - because
 * the third is the one that decides whether a deployment keeps reading rates after its provider has one bad
 * afternoon.
 */
class ScheduledRateIngestionTest {

    private static final Duration REFRESH = Duration.ofHours(6);

    @Test
    void aRefreshReportsWhatThePassFound() {
        RecordingIngestion port = new RecordingIngestion();
        port.report = Optional.of(new RateIngestion("ecb", Instant.parse("2026-09-25T14:00:00Z"),
                6, 6, List.of(), List.of()));

        schedule(port).refresh();

        assertThat(port.calls).as("the pass is made exactly once per tick").hasSize(1);
    }

    @Test
    void aRefreshWithNothingToReadIsSilentRatherThanFailing() {
        RecordingIngestion port = new RecordingIngestion();

        assertThatCode(() -> schedule(port).refresh()).doesNotThrowAnyException();
        assertThat(port.calls).hasSize(1);
    }

    @Test
    void aRefreshSurvivesAFaultSoTheNextOneHappens() {
        RecordingIngestion port = new RecordingIngestion();
        port.fault = new IllegalStateException("the rate feed is unreachable");

        assertThatCode(() -> schedule(port).refresh())
                .as("a scheduled method that throws loses its schedule, and a deployment would then read no "
                        + "rates until somebody restarted it")
                .doesNotThrowAnyException();
        assertThat(port.calls).hasSize(1);
    }

    private static ScheduledRateIngestion schedule(IngestRates ingestion) {
        return new ScheduledRateIngestion(ingestion, new AFeed(), REFRESH);
    }

    private static final class RecordingIngestion implements IngestRates {

        private final List<FxRateFeed> calls = new ArrayList<>();
        private Optional<RateIngestion> report = Optional.empty();
        private RuntimeException fault;

        @Override
        public Optional<RateIngestion> ingest(FxRateFeed feed) {
            calls.add(feed);

            if (fault != null) {
                throw fault;
            }

            return report;
        }
    }

    private static final class AFeed implements FxRateFeed {

        @Override
        public Optional<PublishedRates> latest() {
            return Optional.empty();
        }

        @Override
        public String name() {
            return "test";
        }
    }
}
