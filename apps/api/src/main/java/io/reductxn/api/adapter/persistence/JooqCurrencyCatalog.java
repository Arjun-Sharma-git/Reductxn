package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.ledger.api.Currency;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * The currency catalogue, read from {@code currencies}.
 *
 * <p>A read model over {@code libs/fx}'s table, which is the permitted way to read another module's data
 * (RULE B17). It answers one question - what exponent does this code have - because that is the question the
 * write path cannot avoid: the wire format carries a code and no exponent, and nothing downstream can build
 * a {@code Money} without one.
 *
 * <p>{@code is_cbdc} and {@code jurisdiction} are in the table and not read here. A rail needs the first and
 * tax needs the second, and neither has an implementation yet, so reading them now would be a field nobody
 * consumes - and a field nobody consumes is a field nobody notices is wrong.
 */
@Component
public class JooqCurrencyCatalog implements CurrencyCatalog {

    private static final Table<Record> CURRENCIES = DSL.table("currencies");
    private static final Field<String> CODE = DSL.field("code", String.class);
    private static final Field<Short> MINOR_UNIT_EXPONENT =
            DSL.field("minor_unit_exponent", Short.class);

    private final DSLContext dsl;

    public JooqCurrencyCatalog(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<Currency> byCode(String code) {
        Objects.requireNonNull(code, "code");

        return dsl.select(CODE, MINOR_UNIT_EXPONENT)
                .from(CURRENCIES)
                .where(CODE.eq(code))
                .fetchOptional(row -> Currency.of(row.get(CODE), row.get(MINOR_UNIT_EXPONENT)));
    }
}
