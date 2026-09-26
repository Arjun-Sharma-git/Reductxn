package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.out.TaxRuleCatalog;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.TaxRule;
import io.reductxn.netting.api.TaxRules;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The tax rule in force, read from {@code tax_rules}.
 *
 * <p>A read model rather than a repository: {@code tax_rules} is owned by {@code libs/netting} (RULE B17),
 * and a read model over another module's table is the permitted way to read it. The <em>shape</em> of a
 * rule stays netting's business - this class only turns a row into whatever {@link TaxRules} will build,
 * so the set of shapes exists in exactly one place.
 *
 * <p><strong>Resolving two rules is an error, not a race.</strong> The spec says exactly one rule applies
 * to a {@code (jurisdiction, currency)} on a date. When the data says otherwise, taking the latest would
 * be this class inventing a tie-break rule that nobody agreed to, and the tie-break would then decide a
 * group's tax. So an overlap is refused with both rule ids named, which is a data problem somebody has to
 * fix rather than a number somebody has to defend later.
 *
 * <p><strong>A kind the engine cannot execute is refused too.</strong> {@code docs/specs/tax-rules.md}
 * and ADR 0006 list three shapes; {@code libs/netting} implements flat and ad valorem and a no-tax rule,
 * and no threshold rule. A {@code threshold} row therefore stops the read instead of silently assessing
 * zero tax - because no tax and low tax are indistinguishable in a report, and one of them is wrong.
 */
@Component
public class JooqTaxRuleCatalog implements TaxRuleCatalog {

    private static final Table<Record> TAX_RULES = DSL.table("tax_rules");
    private static final Field<UUID> ID = DSL.field("id", UUID.class);
    private static final Field<String> JURISDICTION = DSL.field("jurisdiction", String.class);
    private static final Field<String> CURRENCY_CODE = DSL.field("currency_code", String.class);
    private static final Field<String> KIND = DSL.field("kind", String.class);
    private static final Field<JSONB> PARAMS = DSL.field("params_json", JSONB.class);
    private final DSLContext dsl;
    private final ObjectMapper json;
    private final String jurisdiction;

    public JooqTaxRuleCatalog(
            DSLContext dsl, ObjectMapper json,
            @Value("${reductxn.tax.jurisdiction}") String jurisdiction) {

        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.json = Objects.requireNonNull(json, "json");
        this.jurisdiction = Objects.requireNonNull(jurisdiction, "jurisdiction");
    }

    @Override
    public TaxRule activeFor(Currency currency, Instant asOf) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(asOf, "asOf");

        List<ActiveRule> active = dsl.select(ID, KIND, PARAMS)
                .from(TAX_RULES)
                .where(JURISDICTION.eq(jurisdiction))
                .and(CURRENCY_CODE.eq(currency.code()))
                // The window is TaxRuleWindow's condition rather than a copy: the rules read shows the same rules
                // this resolves, and the two can only promise that while they ask the same question.
                .and(TaxRuleWindow.inForceAt(asOf))
                .fetch(row -> new ActiveRule(row.get(ID), row.get(KIND), row.get(PARAMS).data()));

        if (active.isEmpty()) {
            // No rule is a rule: the spec's default for record-only deployments. Not an error, because a
            // jurisdiction that has not legislated yet is a normal state.
            return TaxRules.none();
        }

        if (active.size() > 1) {
            throw new IllegalStateException(
                    "tax_rules holds " + active.size() + " rules in force for (" + jurisdiction + ", "
                            + currency.code() + ") at " + asOf + ": " + ids(active)
                            + ". The spec requires exactly one, and choosing one here would invent a "
                            + "tie-break that decides a group's tax.");
        }

        ActiveRule row = active.get(0);
        return build(row.id(), currency, row.kind(), row.paramsJson());
    }

    private static String ids(List<ActiveRule> rules) {
        return rules.stream().map(rule -> rule.id().toString()).toList().toString();
    }

    /** A row, so no jOOQ type is visible outside the fetch that produced it. */
    private record ActiveRule(UUID id, String kind, String paramsJson) {
    }

    /**
     * A row as a rule the engine can execute.
     *
     * <p>Every branch has to be named. A rule shape the engine does not implement stops the read rather
     * than falling through to a default, because assessing nothing and assessing zero are the same number
     * in a report and only one of them is right.
     */
    private TaxRule build(UUID ruleId, Currency currency, String kind, String paramsJson) {
        JsonNode params = params(paramsJson, ruleId);

        return switch (kind) {
            case "flat" -> TaxRules.flatPerTransfer(
                    Money.of(required(params, "perTransferMinor", ruleId), currency));

            case "advalorem" -> TaxRules.adValoremBps(
                    required(params, "rateBps", ruleId),
                    Money.of(optional(params, "minMinor", 0L), currency),
                    // An absent cap is modelled as the largest representable amount, so it never binds.
                    // Money is exact and the assessment is a percentage, so this is a bound and not a
                    // value anything computes with.
                    Money.of(optional(params, "maxMinor", Long.MAX_VALUE), currency));

            case "threshold" -> throw new IllegalStateException(
                    "tax rule " + ruleId + " is kind 'threshold'. docs/specs/tax-rules.md section 2 and "
                            + "ADR 0006 list that shape, but libs/netting implements only flat, ad valorem "
                            + "and a no-tax rule - there is nothing that can assess a threshold. Refusing "
                            + "rather than assessing zero, because a rule that does not apply and a rule "
                            + "that is not implemented are indistinguishable in a report.");

            default -> throw new IllegalStateException(
                    "tax rule " + ruleId + " has kind '" + kind + "', which the tax_rules CHECK constraint "
                            + "should have prevented. Refusing rather than defaulting to no tax.");
        };
    }

    private JsonNode params(String paramsJson, UUID ruleId) {
        JsonNode node = json.readTree(paramsJson);

        if (node == null || !node.isObject()) {
            throw new IllegalStateException(
                    "tax rule " + ruleId + " has params_json that is not an object: " + paramsJson);
        }

        return node;
    }

    private static long required(JsonNode params, String name, UUID ruleId) {
        JsonNode value = params.get(name);

        if (value == null || !value.isNumber()) {
            throw new IllegalStateException(
                    "tax rule " + ruleId + " has no numeric '" + name + "' in its params: " + params);
        }

        return value.asLong();
    }

    private static long optional(JsonNode params, String name, long fallback) {
        JsonNode value = params.get(name);
        return value != null && value.isNumber() ? value.asLong() : fallback;
    }
}
