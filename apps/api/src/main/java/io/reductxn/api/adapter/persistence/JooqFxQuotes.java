package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.FxQuotes;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The locked quotes, in {@code fx_quotes} - which is {@code libs/fx}'s table (RULE B17).
 *
 * <p>A repository here rather than in {@code adapter/rate}, where the rate provider lives, because the two do
 * different jobs: the provider answers a question the domain asked (a rate for a pair), while this writes a
 * row for the module that decided to record a commitment. The rate provider's location is prescribed by
 * {@code libs/fx}'s README; this one belongs with the other repositories.
 *
 * <p>Column constants come from {@link FxQuoteRows}, so the read path and this write path cannot disagree about
 * what a column is called. An insert is the only statement here, which is the whole point of a quote: a
 * correction to a conversion is a compensating entry, never an update (ADR 0004, ADR 0013 point 5).
 */
@Component
public class JooqFxQuotes implements FxQuotes {

    private final DSLContext dsl;

    public JooqFxQuotes(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void record(FxQuote quote) {
        Objects.requireNonNull(quote, "quote");

        FxRate rate = quote.rate();

        dsl.insertInto(FxQuoteRows.FX_QUOTES)
                .columns(FxQuoteRows.ID,
                        FxQuoteRows.BASE_CODE, FxQuoteRows.BASE_EXPONENT, FxQuoteRows.BASE_AMOUNT_MINOR,
                        FxQuoteRows.QUOTE_CODE, FxQuoteRows.QUOTE_EXPONENT,
                        FxQuoteRows.CONVERTED_AMOUNT_MINOR,
                        FxQuoteRows.RESIDUAL_SCALED, FxQuoteRows.RESIDUAL_SCALE,
                        FxQuoteRows.RATE_SCALED, FxQuoteRows.RATE_SCALE, FxQuoteRows.MID_RATE_SCALED,
                        FxQuoteRows.SPREAD_BPS, FxQuoteRows.RATE_SOURCE,
                        FxQuoteRows.RATE_QUOTED_AT, FxQuoteRows.RATE_EXPIRES_AT,
                        FxQuoteRows.LOCKED_AT, FxQuoteRows.RAIL_PERFORMED_FX)
                .values(quote.id(),
                        quote.baseAmount().currency().code(),
                        (short) quote.baseAmount().currency().minorUnitExponent(),
                        quote.baseAmount().amountMinor(),
                        quote.convertedAmount().currency().code(),
                        (short) quote.convertedAmount().currency().minorUnitExponent(),
                        quote.convertedAmount().amountMinor(),
                        quote.residualScaled(), (short) quote.residualScale(),
                        rate.rateScaled(), (short) rate.rateScale(), rate.midRateScaled(),
                        rate.spreadBps(), rate.source(),
                        rate.quotedAt().atOffset(ZoneOffset.UTC),
                        rate.expiresAt().atOffset(ZoneOffset.UTC),
                        quote.lockedAt().atOffset(ZoneOffset.UTC),
                        quote.railPerformedFx())
                .execute();
    }
}
