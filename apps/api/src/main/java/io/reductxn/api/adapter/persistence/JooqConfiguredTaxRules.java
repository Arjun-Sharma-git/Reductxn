package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.ConfiguredTaxRule;
import io.reductxn.api.port.out.ConfiguredTaxRules;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The rules in force, read from {@code tax_rules}.
 *
 * <p>A read model over another module's table, which is what RULE B17 permits: {@code tax_rules} belongs to
 * {@code libs/netting}, and this class turns its rows into the shape the contract promises without interpreting what a
 * rule means. The <em>parameters</em> travel as the JSON the column holds - the shapes are the engine's business, and a
 * second model of them here is the one that goes stale.
 *
 * <p><strong>The window is {@link TaxRuleWindow}'s condition, not a copy of it.</strong> That is the whole reason the
 * expression lives in its own class: the settlement path resolves a rule with the same predicate, so a rule that
 * appears here is a rule a settlement would find.
 *
 * <p><strong>The jurisdiction is the deployment's</strong> ({@code reductxn.tax.jurisdiction}), which is also why the
 * port takes none. A rule for another jurisdiction is not "not in force" - it is not this deployment's business, and
 * the response's own {@code jurisdiction} field is the same property, so a reader can see what was asked.
 *
 * <p>Ordered by currency and then by the window's start, so a response is stable and a rule that was superseded within
 * the same window is visibly adjacent to the one that superseded it.
 */
@Component
public class JooqConfiguredTaxRules implements ConfiguredTaxRules {

    private static final Table<Record> TAX_RULES = DSL.table("tax_rules");
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<String> JURISDICTION = DSL.field("jurisdiction", String.class);
    private static final Field<String> CURRENCY_CODE = DSL.field("currency_code", String.class);
    private static final Field<String> KIND = DSL.field("kind", String.class);
    private static final Field<JSONB> PARAMS = DSL.field("params_json", JSONB.class);
    private static final Field<String> SOURCE_REF = DSL.field("source_ref", String.class);

    private final DSLContext dsl;
    private final String jurisdiction;

    public JooqConfiguredTaxRules(DSLContext dsl, @Value("${reductxn.tax.jurisdiction}") String jurisdiction) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.jurisdiction = Objects.requireNonNull(jurisdiction, "jurisdiction");
    }

    @Override
    public List<ConfiguredTaxRule> inForceAt(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");

        return dsl.select(ID, JURISDICTION, CURRENCY_CODE, KIND, PARAMS, TaxRuleWindow.EFFECTIVE_FROM,
                        TaxRuleWindow.EFFECTIVE_TO, SOURCE_REF)
                .from(TAX_RULES)
                .where(JURISDICTION.eq(jurisdiction))
                .and(TaxRuleWindow.inForceAt(asOf))
                .orderBy(CURRENCY_CODE, TaxRuleWindow.EFFECTIVE_FROM)
                .fetch(rule -> new ConfiguredTaxRule(
                        rule.get(ID),
                        rule.get(JURISDICTION),
                        rule.get(CURRENCY_CODE),
                        rule.get(KIND),
                        // The column's own text rather than a re-serialized tree: `jsonb` normalizes key order and
                        // spacing, so what arrives here is what an administrator inserted. Read as a String rather
                        // than via the JSONB type, so this adapter needs no JSON library at all.
                        rule.get(PARAMS, String.class),
                        instantOf(rule.get(TaxRuleWindow.EFFECTIVE_FROM)),
                        instantOf(rule.get(TaxRuleWindow.EFFECTIVE_TO)),
                        rule.get(SOURCE_REF)));
    }

    /** A null end means the window is open, which is a fact rather than a missing value. */
    private static Instant instantOf(OffsetDateTime stored) {
        return stored == null ? null : stored.toInstant();
    }
}
