package io.reductxn.api.adapter.persistence;

import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.FxRateProvider;
import io.reductxn.fx.api.StaleRateException;
import io.reductxn.fx.api.UnavailableRateException;
import io.reductxn.ledger.api.Currency;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Rates, read from {@code fx_rates} - {@code libs/fx}'s table, through a read model (RULE B17).
 *
 * <p>This is the adapter {@code libs/fx}'s port talks about, and it lives in this package rather than in a
 * package of its own because RULE B16 keeps every jOOQ type inside {@code adapter/persistence} - so a rate
 * source that reads a table cannot be anywhere else. The port is the module's, so the module can be tested
 * against a fixed rate with no provider, no network and no container; where the rate then comes from is this
 * file's problem.
 *
 * <p><strong>Two failures, and they are deliberately different.</strong> A pair with no rate quoted at or
 * before the instant is {@link UnavailableRateException} - the server cannot answer, and a retry may succeed.
 * A pair whose most recent rate had already expired is {@link StaleRateException} - the request can be
 * answered and the answer is no, because locking it would promise a creditor an amount computed from a price
 * the market has moved away from (ADR 0013 point 6). Both codes are in the contract, one as a 503 and one as
 * a 422, and a client that conflated them would retry forever or give up too early.
 *
 * <p>Returning the newest rate *and judging it* rather than filtering to usable ones is what makes those two
 * distinguishable at all: a query for "usable rates only" cannot tell a stale rate from an absent one, and the
 * difference is the difference between "try again" and "do not bother".
 *
 * <p>This reads rather than computes. It does not apply a spread, choose between providers, or cache: the
 * first two are policy the spec leaves open (section 11), the third would reintroduce exactly the "cached
 * value of unknown age" the port forbids.
 */
@Component
public class JooqFxRateProvider implements FxRateProvider {

    private static final Table<Record> FX_RATES = DSL.table("fx_rates");
    private static final Field<String> BASE_CODE = DSL.field("base_code", String.class);
    private static final Field<Short> BASE_EXPONENT = DSL.field("base_exponent", Short.class);
    private static final Field<String> QUOTE_CODE = DSL.field("quote_code", String.class);
    private static final Field<Short> QUOTE_EXPONENT = DSL.field("quote_exponent", Short.class);
    private static final Field<Long> RATE_SCALED = DSL.field("rate_scaled", Long.class);
    private static final Field<Short> RATE_SCALE = DSL.field("rate_scale", Short.class);
    private static final Field<Long> MID_RATE_SCALED = DSL.field("mid_rate_scaled", Long.class);
    private static final Field<Integer> SPREAD_BPS = DSL.field("spread_bps", Integer.class);
    private static final Field<String> SOURCE = DSL.field("source", String.class);
    private static final Field<OffsetDateTime> QUOTED_AT = DSL.field("quoted_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> EXPIRES_AT = DSL.field("expires_at", OffsetDateTime.class);

    private final DSLContext dsl;

    public JooqFxRateProvider(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public FxRate rateFor(Currency base, Currency quote, Instant asOf) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(asOf, "asOf");

        OffsetDateTime asOfUtc = asOf.atOffset(ZoneOffset.UTC);

        // At or before the instant, not merely "the latest": a preview or a replay of a past moment has to see
        // the rate that was quoted by then, or a past obligation would be re-derivable from today's market.
        FxRate rate = dsl.select(BASE_CODE, BASE_EXPONENT, QUOTE_CODE, QUOTE_EXPONENT, RATE_SCALED,
                        RATE_SCALE, MID_RATE_SCALED, SPREAD_BPS, SOURCE, QUOTED_AT, EXPIRES_AT)
                .from(FX_RATES)
                .where(BASE_CODE.eq(base.code()))
                .and(QUOTE_CODE.eq(quote.code()))
                .and(QUOTED_AT.le(asOfUtc))
                .orderBy(QUOTED_AT.desc())
                .limit(1)
                .fetchOne(JooqFxRateProvider::toRate);

        if (rate == null) {
            throw new UnavailableRateException(base, quote, asOf,
                    "no rate for this pair was quoted at or before this instant");
        }

        if (!rate.isUsableAt(asOf)) {
            throw new StaleRateException(
                    base, quote, rate.quotedAt(), rate.expiresAt(), asOf);
        }

        return rate;
    }

    @Override
    public String name() {
        return "reductxn.fx_rates";
    }

    /** One row as a rate. The constructor is the check: an invalid rate cannot come out of here. */
    private static FxRate toRate(Record row) {
        return new FxRate(
                Currency.of(row.get(BASE_CODE), row.get(BASE_EXPONENT)),
                Currency.of(row.get(QUOTE_CODE), row.get(QUOTE_EXPONENT)),
                row.get(RATE_SCALED),
                row.get(RATE_SCALE),
                row.get(MID_RATE_SCALED),
                row.get(SPREAD_BPS),
                row.get(QUOTED_AT).toInstant(),
                row.get(EXPIRES_AT).toInstant(),
                row.get(SOURCE));
    }
}
