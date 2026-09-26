package io.reductxn.settlement.port.out;

import io.reductxn.ledger.api.Currency;
import io.reductxn.netting.api.TaxRule;

import java.time.Instant;

/**
 * The tax rule in force for a currency at an instant.
 *
 * <p>Tax law is data selected by {@code (jurisdiction, currency, effective date)} and inserted rather than
 * coded (ADR 0006), so a use case needs a lookup and not a branch on country. Effective-dating is why it is
 * asked at an instant: a run over a past cycle has to be assessed under the rule that applied then, or the same
 * cycle would settle for a different amount depending on when the run was computed.
 *
 * <p>Reads {@code tax_rules}, which is {@code libs/netting}'s table, as a read model (RULE B17). Identical in
 * shape to the API's port of the same name, and deliberately so: if the two resolved different rules for one
 * currency, a preview and a run would quote different tax for the same cycle and only one of them would be
 * right.
 *
 * <p>No matching rule returns {@code TaxRules.none()} rather than throwing: a jurisdiction that has not
 * legislated yet is a normal state, and it is the spec's stated default rather than a gap. What is <em>not</em>
 * tolerated is an overlap - two rules in force for one {@code (jurisdiction, currency)} - because choosing one
 * there would invent a tie-break that decides a group's tax.
 */
public interface TaxRuleCatalog {

    /** The rule that applies to {@code currency} at {@code asOf}, or no tax when none has been legislated. */
    TaxRule activeFor(Currency currency, Instant asOf);
}
