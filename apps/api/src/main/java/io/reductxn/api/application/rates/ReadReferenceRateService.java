package io.reductxn.api.application.rates;

import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.ReadReferenceRate;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.fx.api.FxException;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.FxRateProvider;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * The use case: the rate a pair converts at now, or one of the two reasons it does not.
 *
 * <p>It does three things and no more: resolve both codes through the catalogue, ask the provider for the rate as of
 * the clock's instant, and let the provider's own failure travel as the answer. In particular it does <em>not</em>
 * judge staleness itself - that judgement is the provider's (it is the one holding the rate and the policy that says
 * how old a rate may be), and a second opinion here would be the second description of one rule that ADR 0021
 * forbids. The consequence is that this read and the recording path fail identically, which is the point.
 *
 * <p>The clock rather than {@code Instant.now()} for the reason the recording path gives: an instant is an input, and
 * a use case that reads the system clock cannot be tested at a moment it chooses - here the moment is what decides
 * whether a rate has expired.
 */
@Service
public class ReadReferenceRateService implements ReadReferenceRate {

    private final CurrencyCatalog currencies;
    private final FxRateProvider rates;
    private final Clock clock;

    public ReadReferenceRateService(CurrencyCatalog currencies, FxRateProvider rates, Clock clock) {
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<FxRate> forPair(String base, String quote) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");

        Optional<Currency> baseCurrency = currencies.byCode(base);
        if (baseCurrency.isEmpty()) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(base));
        }

        Optional<Currency> quoteCurrency = currencies.byCode(quote);
        if (quoteCurrency.isEmpty()) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(quote));
        }

        try {
            return Result.ok(rates.rateFor(baseCurrency.orElseThrow(), quoteCurrency.orElseThrow(), clock.instant()));
        } catch (FxException expected) {
            // The provider's own code and message, not a rewording: the client branches on the code, and it is the
            // only thing that knows whether the rate was absent or expired (the recording path says the same, and
            // says it for the same reason).
            return Result.fail(expected);
        }
    }
}
