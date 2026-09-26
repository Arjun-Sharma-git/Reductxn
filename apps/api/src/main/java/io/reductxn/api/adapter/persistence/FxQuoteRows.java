package io.reductxn.api.adapter.persistence;

import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code fx_quotes} columns, and the one mapping from a row to a locked quote.
 *
 * <p>Shared by the three repositories that touch the table - the two in this package that read a quote
 * alongside an obligation, and the one that writes a quote - so a column is renamed in one place. Package
 * private, like the row types it deals in: nothing outside this package needs to know what a quote row looks
 * like (RULE B16).
 *
 * <p><strong>Reading is by id, in one query, rather than a join in the obligations' SQL.</strong> A join would
 * need every column qualified by an alias and therefore a second set of field constants, or the same names
 * written twice in two forms - which is the kind of duplication a rename then half-applies. Two queries, one
 * of them batched over the ids the first returned, keeps one set of names and keeps the N in N+1 at zero. The
 * cost is one extra round trip on a read path that has no latency budget to speak of.
 *
 * <p>Rebuilding through the record constructors is deliberate: {@code FxRate} and {@code FxQuote} both refuse
 * to exist malformed, so a row that got past the CHECK constraints and still broke an invariant fails here
 * rather than being trusted.
 */
final class FxQuoteRows {

    static final Table<Record> FX_QUOTES = DSL.table("fx_quotes");
    static final Field<UUID> ID = DSL.field("id", UUID.class);
    static final Field<String> BASE_CODE = DSL.field("base_code", String.class);
    static final Field<Short> BASE_EXPONENT = DSL.field("base_exponent", Short.class);
    static final Field<Long> BASE_AMOUNT_MINOR = DSL.field("base_amount_minor", Long.class);
    static final Field<String> QUOTE_CODE = DSL.field("quote_code", String.class);
    static final Field<Short> QUOTE_EXPONENT = DSL.field("quote_exponent", Short.class);
    static final Field<Long> CONVERTED_AMOUNT_MINOR = DSL.field("converted_amount_minor", Long.class);
    static final Field<Long> RESIDUAL_SCALED = DSL.field("residual_scaled", Long.class);
    static final Field<Short> RESIDUAL_SCALE = DSL.field("residual_scale", Short.class);
    static final Field<Long> RATE_SCALED = DSL.field("rate_scaled", Long.class);
    static final Field<Short> RATE_SCALE = DSL.field("rate_scale", Short.class);
    static final Field<Long> MID_RATE_SCALED = DSL.field("mid_rate_scaled", Long.class);
    static final Field<Integer> SPREAD_BPS = DSL.field("spread_bps", Integer.class);
    static final Field<String> RATE_SOURCE = DSL.field("rate_source", String.class);
    static final Field<OffsetDateTime> RATE_QUOTED_AT =
            DSL.field("rate_quoted_at", OffsetDateTime.class);
    static final Field<OffsetDateTime> RATE_EXPIRES_AT =
            DSL.field("rate_expires_at", OffsetDateTime.class);
    static final Field<OffsetDateTime> LOCKED_AT = DSL.field("locked_at", OffsetDateTime.class);
    static final Field<Boolean> RAIL_PERFORMED_FX = DSL.field("rail_performed_fx", Boolean.class);

    private static final Field<?>[] ALL_COLUMNS = {
            ID, BASE_CODE, BASE_EXPONENT, BASE_AMOUNT_MINOR, QUOTE_CODE, QUOTE_EXPONENT,
            CONVERTED_AMOUNT_MINOR, RESIDUAL_SCALED, RESIDUAL_SCALE, RATE_SCALED, RATE_SCALE,
            MID_RATE_SCALED, SPREAD_BPS, RATE_SOURCE, RATE_QUOTED_AT, RATE_EXPIRES_AT, LOCKED_AT,
            RAIL_PERFORMED_FX
    };

    private FxQuoteRows() {
    }

    /**
     * The quotes with these ids, keyed by id. Empty input is answered without a query.
     *
     * <p>A missing id is simply absent from the map rather than an error. The caller is an obligation whose
     * {@code quote_id} points at nothing, and what that means - a row written by hand, or a quote deleted
     * against the append-only rule - is the caller's to report, with the obligation's id in the message.
     */
    static Map<UUID, FxQuote> byId(DSLContext dsl, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<UUID, FxQuote> quotes = new HashMap<>();
        for (Record row : dsl.select(ALL_COLUMNS).from(FX_QUOTES).where(ID.in(ids)).fetch()) {
            FxQuote quote = toQuote(row);
            quotes.put(quote.id(), quote);
        }

        return quotes;
    }

    /** One row as the locked conversion it records. */
    static FxQuote toQuote(Record row) {
        Currency base = Currency.of(row.get(BASE_CODE), row.get(BASE_EXPONENT));
        Currency quote = Currency.of(row.get(QUOTE_CODE), row.get(QUOTE_EXPONENT));

        FxRate rate = new FxRate(
                base,
                quote,
                row.get(RATE_SCALED),
                row.get(RATE_SCALE),
                row.get(MID_RATE_SCALED),
                row.get(SPREAD_BPS),
                row.get(RATE_QUOTED_AT).toInstant(),
                row.get(RATE_EXPIRES_AT).toInstant(),
                row.get(RATE_SOURCE));

        return new FxQuote(
                row.get(ID),
                rate,
                Money.of(row.get(BASE_AMOUNT_MINOR), base),
                Money.of(row.get(CONVERTED_AMOUNT_MINOR), quote),
                row.get(RESIDUAL_SCALED),
                row.get(RESIDUAL_SCALE),
                row.get(LOCKED_AT).toInstant(),
                row.get(RAIL_PERFORMED_FX));
    }
}
