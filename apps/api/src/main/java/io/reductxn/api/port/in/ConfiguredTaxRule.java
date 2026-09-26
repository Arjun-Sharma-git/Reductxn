package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One tax rule as configured: a rate for a {@code (jurisdiction, currency)} over an effective window.
 *
 * <p><strong>Not {@code io.reductxn.netting.api.TaxRule}, and the difference is the point.</strong> That type is a
 * rule the engine can *execute* - it assesses an amount and declares the objective the optimizer should pursue - and
 * it carries neither an identifier nor a jurisdiction nor a statute. This is the same rule as *data*: what an
 * administrator inserted, effective-dated, with the reference that lets somebody defend it. A read of the rules table
 * is a read of rows, and dressing those rows up as executable rules would mean building engine objects in an inbound
 * adapter for nobody's benefit.
 *
 * <p><strong>{@code paramsJson} travels verbatim.</strong> The three shapes ({@code flat}, {@code advalorem},
 * {@code threshold}) have their own keys, and they belong to the module that assesses them - {@code libs/netting} and
 * {@code docs/specs/tax-rules.md} section 2. Interpreting a rule's parameters here would be a second model of them,
 * and the second model is the one that goes stale when a fourth shape arrives. The web layer turns the string into
 * the JSON object the contract promises; this layer only carries it.
 *
 * <p>{@code effectiveFrom} and {@code effectiveTo} are instants rather than days because that is what the column
 * holds and what the settlement path compares. {@code effectiveTo} is null for a rule with no end, which is an
 * unbounded window rather than a missing answer.
 */
public record ConfiguredTaxRule(
        UUID id,
        String jurisdiction,
        String currency,
        String kind,
        String paramsJson,
        Instant effectiveFrom,
        Instant effectiveTo,
        String sourceRef) {

    public ConfiguredTaxRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jurisdiction, "jurisdiction");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(paramsJson, "paramsJson");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(sourceRef, "sourceRef");
        // `effectiveTo` is deliberately not required: a rule with no end is the ordinary case for a rate that is
        // still in force, not a row missing a value.
    }
}
