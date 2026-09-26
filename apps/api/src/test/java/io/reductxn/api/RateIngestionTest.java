package io.reductxn.api;

import io.reductxn.api.application.rates.IngestRatesService;
import io.reductxn.api.port.in.IngestRates;
import io.reductxn.api.port.in.RateIngestion;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.api.port.out.RateStore;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.FxRateFeed;
import io.reductxn.fx.api.PublishedRates;
import io.reductxn.ledger.api.Currency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One pass of rate ingestion, with no database and no network.
 *
 * <p>What is checked here is the sequence rather than the arithmetic - the derivation has its own tests in
 * {@code libs/fx}, and the writing has a container test beside this one. So the store is a list and the
 * catalogue is a set, and the assertions are about what the pass decided: which pairs it offered, which codes it
 * could not use either way, and that a provider's silence is not reported as a failure.
 */
class RateIngestionTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency RUB = Currency.of("RUB", 2);
    private static final Currency USD = Currency.of("USD", 2);

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-25T14:00:00Z");

    /** The ECB's own values for 25 September 2026, at the five-decimal scale its document uses. */
    private static final PublishedRates PUBLICATION = new PublishedRates("ecb", "EUR", 5, PUBLISHED_AT,
            Map.of("USD", 114_030L, "JPY", 17_970_000L, "INR", 10_926_050L, "GBP", 86_045L));

    private static final Duration MAX_AGE = Duration.ofDays(4);

    private final Catalogue catalogue = new Catalogue(Set.of(INR, JPY, RUB, USD));
    private final Store store = new Store();

    private final IngestRates ingestion = new IngestRatesService(catalogue, store, MAX_AGE);

    @Test
    void ingestsThePairsThisDeploymentCanTransact() {
        RateIngestion report = ingestion.ingest(new Feed(PUBLICATION)).orElseThrow();

        assertThat(report.provider()).isEqualTo("ecb");
        assertThat(report.publishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(report.pairsOffered())
                .as("three of the four currencies this deployment knows are quoted, so six ordered pairs")
                .isEqualTo(6);
        assertThat(report.pairsWritten()).isEqualTo(6);
        assertThat(report.wroteAnything()).isTrue();
        assertThat(store.recorded).hasSize(6);
    }

    @Test
    void storesWhatTheDerivationProducedRatherThanItsOwnArithmetic() {
        ingestion.ingest(new Feed(PUBLICATION));

        FxRate rupeesPerYen = store.recorded.stream()
                .filter(rate -> rate.base().equals(JPY) && rate.quote().equals(INR))
                .findFirst()
                .orElseThrow();

        // 109.2605 / 179.70, from libs/fx's derivation, at eight digits.
        assertThat(rupeesPerYen.rateScaled()).isEqualTo(60_801_614L);
        assertThat(rupeesPerYen.source())
                .as("a cross the provider never published says so in the row an auditor reads")
                .isEqualTo("ecb-cross");
        assertThat(rupeesPerYen.expiresAt())
                .as("the freshness window is the deployment's, and it lands on the stored rate")
                .isEqualTo(PUBLISHED_AT.plus(MAX_AGE));
        assertThat(rupeesPerYen.quotedAt()).isEqualTo(PUBLISHED_AT);
    }

    @Test
    void reportsBothKindsOfCodeItCouldNotUse() {
        RateIngestion report = ingestion.ingest(new Feed(PUBLICATION)).orElseThrow();

        assertThat(report.unquotedCurrencies())
                .as("this deployment can transact RUB and the bank prices it nowhere, so every obligation in it "
                        + "will be refused until somebody acts")
                .containsExactly("RUB");
        assertThat(report.unknownCurrencies())
                .as("the bank quotes far more currencies than this deployment knows, which is information "
                        + "rather than a problem")
                .containsExactly("GBP");
    }

    @Test
    void aProviderWithNothingToSayIsNotAFailure() {
        Optional<RateIngestion> report = ingestion.ingest(new SilentFeed());

        assertThat(report).as("empty is a fact about the provider, not an error about the deployment").isEmpty();
        assertThat(store.recorded).isEmpty();
    }

    @Test
    void aFeedThatCannotBeObtainedIsAFaultTheJobReports() {
        assertThatThrownBy(() -> ingestion.ingest(new BrokenFeed()))
                .as("a deployment that turned this into an empty publication would look calm with no rates")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
        assertThat(store.recorded).isEmpty();
    }

    @Test
    void ingestingTheSamePublicationTwiceWritesNothingTheSecondTime() {
        ingestion.ingest(new Feed(PUBLICATION));
        store.recorded.clear();
        store.alreadyStored = true;

        RateIngestion second = ingestion.ingest(new Feed(PUBLICATION)).orElseThrow();

        assertThat(second.pairsOffered()).as("the same publication still implies the same pairs").isEqualTo(6);
        assertThat(second.pairsWritten()).as("none of them is new, which is how a repeat is told from a day")
                .isZero();
        assertThat(second.wroteAnything()).isFalse();
    }

    // --- the two collaborators, as the smallest things that satisfy their ports -----------------

    private record Catalogue(Set<Currency> currencies) implements CurrencyCatalog {

        @Override
        public Optional<Currency> byCode(String code) {
            return currencies.stream().filter(currency -> currency.code().equals(code)).findFirst();
        }

        @Override
        public Set<Currency> all() {
            return currencies;
        }
    }

    private static final class Store implements RateStore {

        private final List<FxRate> recorded = new ArrayList<>();
        private boolean alreadyStored;

        @Override
        public int record(List<FxRate> rates) {
            if (alreadyStored) {
                return 0;
            }

            recorded.addAll(rates);

            return rates.size();
        }
    }

    private record Feed(PublishedRates published) implements FxRateFeed {

        @Override
        public Optional<PublishedRates> latest() {
            return Optional.of(published);
        }

        @Override
        public String name() {
            return published.provider();
        }
    }

    private static final class SilentFeed implements FxRateFeed {

        @Override
        public Optional<PublishedRates> latest() {
            return Optional.empty();
        }

        @Override
        public String name() {
            return "silent";
        }
    }

    private static final class BrokenFeed implements FxRateFeed {

        @Override
        public Optional<PublishedRates> latest() {
            throw new IllegalStateException("the rate feed is unreachable");
        }

        @Override
        public String name() {
            return "broken";
        }
    }
}
