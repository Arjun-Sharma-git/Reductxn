package io.reductxn.api.port.out;

import io.reductxn.api.port.in.ConfiguredTaxRule;

import java.time.Instant;
import java.util.List;

/**
 * The tax rules a deployment has configured, read from {@code tax_rules}.
 *
 * <p>A read model over another module's table, which is the permitted way to read it: {@code tax_rules} is owned by
 * {@code libs/netting} (RULE B17), and this port says nothing about how a rule is stored - only that a set of them
 * exists for a moment. The read is deliberately *not* routed through the module's own engine objects, because a
 * configured rule and an executable rule are different things and the list a reader needs is the first one.
 *
 * <p><strong>There is no method to write a rule, and there will not be one here.</strong> Insertion is an
 * administrative act with its own governance question - who may insert a rule, and how it is validated against the
 * statute - and the module's own {@code TaxRuleCatalog} port already says so. A read port that grew a write would
 * answer that question by accident.
 */
public interface ConfiguredTaxRules {

    /** Every rule in force at {@code asOf}, ordered by currency and then by the window's start. */
    List<ConfiguredTaxRule> inForceAt(Instant asOf);
}
