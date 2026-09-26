package io.reductxn.api.application.tax;

import io.reductxn.api.port.in.ConfiguredTaxRule;
import io.reductxn.api.port.in.ReadTaxRules;
import io.reductxn.api.port.out.ConfiguredTaxRules;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The use case: the tax rules in force.
 *
 * <p>The thinnest service in this module - one read, no decision, not even a sort, because the read model orders by
 * currency and window so a superseded rule sits beside the one that superseded it. What it owns is the transaction,
 * read-only, and the boundary between "the caller asked about an instant" and "the caller wants it now": the instant
 * arrives as a parameter because a use case that read a clock could not be tested without one, and the controller
 * decides what "now" is.
 *
 * <p>It exists as a use case rather than as a controller calling a read model for the reason RULE B12 gives, and the
 * reason is not ceremony: the endpoint has been in the contract with no implementation, and the missing piece was this
 * port-in (ADR 0038 recorded the gap, ADR 0040 closes it).
 */
@Service
public class ReadTaxRulesService implements ReadTaxRules {

    private final ConfiguredTaxRules rules;

    public ReadTaxRulesService(ConfiguredTaxRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConfiguredTaxRule> inForce(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");

        return rules.inForceAt(asOf);
    }
}
