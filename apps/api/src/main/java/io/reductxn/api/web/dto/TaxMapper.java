package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.ConfiguredTaxRule;
import io.reductxn.api.port.in.TaxReportLine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Maps the tax reads onto the wire format the contract defines.
 *
 * <p><strong>The one mapper in this package that is not a static function, and the reason is worth stating.</strong>
 * {@code NettingPreviewMapper} and the rest have no collaborators on purpose - a mapper that needed one would be a sign
 * that a use case was missing. This one needs the application's own object mapper, because a configured rule's
 * parameters are stored as {@code jsonb} and the contract promises an object: the alternative was a static mapper with
 * a second {@code ObjectMapper} of its own, which is a second JSON configuration to keep in step with the first. The
 * collaborator here is the serializer itself rather than a service, so the rule it deviates from is not the rule it is
 * breaking.
 *
 * <p>It is also where the report's gross tax is recovered: {@code netTax + taxSaved}, the same arithmetic the preview's
 * mapper does for one partition, so a line over a single run is that run's row and the three figures cannot drift.
 */
@Component
public class TaxMapper {

    private final ObjectMapper json;

    public TaxMapper(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public TaxRuleResponse toResponse(ConfiguredTaxRule rule) {
        return new TaxRuleResponse(
                rule.id(),
                rule.jurisdiction(),
                rule.currency(),
                rule.kind(),
                readTree(rule),
                rule.effectiveFrom(),
                rule.effectiveTo(),
                rule.sourceRef());
    }

    public static TaxReportLineResponse toResponse(TaxReportLine line) {
        return new TaxReportLineResponse(
                line.currency(),
                money(line.grossTaxMinor(), line.currency()),
                money(line.netTaxMinor(), line.currency()),
                money(line.taxSavedMinor(), line.currency()),
                line.runCount());
    }

    /**
     * The rule's parameters as the JSON the column holds.
     *
     * <p>Parsing here rather than in the adapter keeps JSON out of the persistence layer entirely - the read model
     * hands over the column's text and nothing else - and it is why a corrupt {@code params_json} fails at the
     * boundary with the rule's own id in the message rather than at the database.
     */
    private JsonNode readTree(ConfiguredTaxRule rule) {
        JsonNode params = json.readTree(rule.paramsJson());

        if (params == null || !params.isObject()) {
            throw new IllegalStateException(
                    "tax rule " + rule.id() + " has params that are not an object: " + rule.paramsJson());
        }

        return params;
    }

    /** A plan figure as the wire's amount: minor units and a code, never an exponent and never a float. */
    private static MoneyDto money(long amountMinor, String currency) {
        return new MoneyDto(amountMinor, currency);
    }
}
