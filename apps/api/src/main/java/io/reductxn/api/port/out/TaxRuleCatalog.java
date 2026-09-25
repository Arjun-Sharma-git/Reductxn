package io.reductxn.api.port.out;

import io.reductxn.ledger.api.Currency;
import io.reductxn.netting.api.TaxRule;

import java.time.Instant;

/**
 * The tax rule in force for a currency at an instant.
 *
 * <p>Tax law is data selected by {@code (jurisdiction, currency, effective date)} and inserted rather
 * than coded (ADR 0006), so what a use case needs is a lookup, not a branch on country. Rule changes are
 * effective-dated, which is why the query is at an instant: a preview of a past cycle must be assessed
 * under the rule that applied then, or the same cycle would report two different figures depending on
 * when someone looked.
 *
 * <p>There is deliberately no method to write a rule. Insertion is an administrative act with its own
 * governance question - who may insert one, and how it is validated against the statute - and folding it
 * into the read port would answer that by accident.
 */
public interface TaxRuleCatalog {

    /** The rule that applies to {@code currency} at {@code asOf}. */
    TaxRule activeFor(Currency currency, Instant asOf);
}
