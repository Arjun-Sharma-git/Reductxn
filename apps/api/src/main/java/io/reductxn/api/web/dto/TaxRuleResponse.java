package io.reductxn.api.web.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code TaxRule} schema from {@code contracts/openapi/reductxn-api.yaml}.
 *
 * <p><strong>{@code effectiveTo} is emitted as null rather than omitted, and that is deliberate.</strong> The schema
 * declares it {@code nullable}, which is a promise about the key being present; the preview's response does the
 * opposite for its own optional fields and documents why (an absent key means "not applicable" there). Here the
 * question a reader asks is "does this rule have an end", and both answers are values rather than absences - so no
 * {@code @JsonInclude} on this record, where every other DTO in this package has one.
 *
 * <p>{@code params} is a tree rather than a type. The three shapes have their own keys and belong to the module that
 * assesses them, so the contract promises an object and this carries an object; naming the keys here would be the
 * second model of a rule's parameters that this module has twice refused to write.
 */
public record TaxRuleResponse(
        UUID id,
        String jurisdiction,
        String currency,
        String kind,
        JsonNode params,
        Instant effectiveFrom,
        Instant effectiveTo,
        String sourceRef) {
}
