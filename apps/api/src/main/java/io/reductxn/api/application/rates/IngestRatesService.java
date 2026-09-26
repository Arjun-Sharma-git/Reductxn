package io.reductxn.api.application.rates;

import io.reductxn.api.port.in.IngestRates;
import io.reductxn.api.port.in.RateIngestion;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.api.port.out.RateStore;
import io.reductxn.fx.api.CrossRates;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.FxRateFeed;
import io.reductxn.fx.api.PublishedRates;
import io.reductxn.ledger.api.Currency;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The use case: turn a provider's publication into the rates this deployment can transact with.
 *
 * <p><strong>Which pairs, and why the catalogue decides.</strong> A provider publishes observations against one
 * base; a deployment can transact the currencies in its own catalogue. So the pairs worth storing are the
 * intersection - not every pair the provider could express, which for thirty quoted currencies is nearly nine
 * hundred rows a day of rates nobody can oblige in, and not a configured list, which would be a second statement
 * of what the catalogue already says. Adding a currency to the catalogue is an insert rather than a release
 * ({@code V401} says so), and the next refresh prices its pairs for the same reason.
 *
 * <p><strong>Freshness is the deployment's, and it is why a daily feed is usable at all.</strong>
 * {@code reductxn.rates.max-age} is how old a published rate may be when a client locks a quote. The default is
 * four days, which is not a guess about markets: a provider that publishes on working days leaves Friday's rate
 * as the most recent one until Monday afternoon, so a bound shorter than a weekend would refuse every
 * cross-currency obligation for it while the provider is behaving perfectly. A streaming provider would use
 * seconds, and the value is per deployment rather than per provider for that reason.
 *
 * <p><strong>No spread, and that is a decision rather than an omission.</strong> The rate stored is the
 * provider's own mid rate, with {@code spread_bps} zero - the two columns exist so a deployment can apply a
 * margin and still disclose what it applied, and applying one is a decision about what a creditor is promised
 * rather than part of loading market data. {@code FxRate.ofMid} states it in the value: no spread was applied,
 * rather than leaving a reader to infer it from two equal fields.
 *
 * <p><strong>A refuse-what-it-cannot-use pass.</strong> The publication is composed in one call and stored in
 * one pass, so a publication that cannot be derived - a currency the feed does not quote, a rate outside the
 * bounds - throws before anything is written, instead of storing one direction of a pair and leaving the other
 * to fail closed at an unpredictable moment.
 */
@Service
public class IngestRatesService implements IngestRates {

    private final CurrencyCatalog currencies;
    private final RateStore rates;
    private final Duration maxAge;

    public IngestRatesService(
            CurrencyCatalog currencies,
            RateStore rates,
            @Value("${reductxn.rates.max-age:P4D}") Duration maxAge) {

        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
    }

    @Override
    public Optional<RateIngestion> ingest(FxRateFeed feed) {
        Objects.requireNonNull(feed, "feed");

        Optional<PublishedRates> fetched = feed.latest();

        if (fetched.isEmpty()) {
            return Optional.empty();
        }

        PublishedRates published = fetched.get();
        Set<Currency> known = currencies.all();
        Set<String> knownCodes = known.stream().map(Currency::code).collect(Collectors.toSet());

        List<Currency> quoted = known.stream()
                .filter(currency -> published.quotes(currency.code()))
                .sorted(Comparator.comparing(Currency::code))
                .toList();

        List<String> unquoted = known.stream()
                .filter(currency -> !published.quotes(currency.code()))
                .map(Currency::code)
                .sorted()
                .toList();

        List<String> unknown = published.codes().stream()
                .filter(code -> !knownCodes.contains(code))
                .toList();

        List<FxRate> derived = CrossRates.ratesFor(published, quoted, maxAge);

        return Optional.of(new RateIngestion(
                feed.name(), published.publishedAt(), derived.size(), rates.record(derived),
                unknown, unquoted));
    }
}
