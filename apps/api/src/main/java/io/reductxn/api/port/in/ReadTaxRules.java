package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.List;

/**
 * The use case: which tax rules are in force right now.
 *
 * <p><strong>It takes an instant rather than reading the clock</strong>, like every other read here, and the reason is
 * the same one: a use case that read a clock could not be tested without one, and the boundary that decides what "now"
 * means is the controller. The instant matters more than usual for this read - a rule's window is an effective-dated
 * interval, so "which rules apply" is a question about a moment rather than about a table.
 *
 * <p><strong>Why this read exists.</strong> A group is charged tax by rules that were inserted rather than released
 * (ADR 0006), and until this endpoint existed nothing could say which of them were in force: the recording path and the
 * preview resolved a rule internally and reported only the resulting amount. A member looking at a settlement could
 * see what the tax came to and not what it was computed under - which is the difference between a number and a number
 * anybody can justify.
 *
 * <p>The jurisdiction is not a parameter. It is a property of the deployment ({@code reductxn.tax.jurisdiction}), so a
 * caller cannot ask for another one and the response's {@code jurisdiction} field is that deployment's. A caller who
 * needs a second jurisdiction needs a second deployment, which is a licensing question rather than a query parameter.
 */
public interface ReadTaxRules {

    /** Every rule in force at {@code asOf}, ordered by currency and then by the window's start. */
    List<ConfiguredTaxRule> inForce(Instant asOf);
}
