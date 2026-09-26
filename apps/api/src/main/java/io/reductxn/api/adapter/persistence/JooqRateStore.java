package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.RateStore;
import io.reductxn.fx.api.FxRate;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Rates, written to {@code fx_rates}.
 *
 * <p>The second thing in this module that touches that table, and the reason it is in this package: RULE B16
 * keeps every jOOQ type inside {@code adapter/persistence}, and this adapter writes a table.
 *
 * <p><strong>{@code ON CONFLICT DO NOTHING} is the whole implementation of idempotence.</strong> The conflict it
 * accepts is the one {@code V403} declares - a rate this provider already quoted for this pair at this instant -
 * and any other conflict remains an error, because there is none: the table's keys are the primary key and that
 * one. So a refresh cannot silently swallow a real fault by being generous with conflicts.
 *
 * <p>One statement per rate rather than one batch. A deployment's own currencies are the pairs it ingests, which
 * is tens of rows rather than thousands, and the count this returns is then exactly the number of rows the
 * database accepted instead of a batch's array of counts that a caller has to add up - and a batch that fails
 * halfway is a state the ingestion would have to describe.
 */
@Component
public class JooqRateStore implements RateStore {

    private static final Table<Record> FX_RATES = DSL.table("fx_rates");
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
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

    public JooqRateStore(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public int record(List<FxRate> rates) {
        Objects.requireNonNull(rates, "rates");

        int written = 0;

        for (FxRate rate : rates) {
            written += dsl.insertInto(FX_RATES)
                    .columns(ID, BASE_CODE, BASE_EXPONENT, QUOTE_CODE, QUOTE_EXPONENT, RATE_SCALED,
                            RATE_SCALE, MID_RATE_SCALED, SPREAD_BPS, SOURCE, QUOTED_AT, EXPIRES_AT)
                    .values(UUID.randomUUID(), rate.base().code(),
                            (short) rate.base().minorUnitExponent(), rate.quote().code(),
                            (short) rate.quote().minorUnitExponent(), rate.rateScaled(),
                            (short) rate.rateScale(), rate.midRateScaled(), rate.spreadBps(),
                            rate.source(), rate.quotedAt().atOffset(ZoneOffset.UTC),
                            rate.expiresAt().atOffset(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute();
        }

        return written;
    }
}
