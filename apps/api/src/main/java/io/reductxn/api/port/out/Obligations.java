package io.reductxn.api.port.out;

import io.reductxn.api.port.in.ObligationFilter;
import io.reductxn.api.port.in.RecordedObligation;

import java.util.List;

/**
 * The obligations this module owns, as a page.
 *
 * <p>A port of its own rather than a method on {@link ObligationStore}, and the split is the one this module already
 * makes elsewhere: the store answers "record this" and "is this already on the record" - both about one obligation
 * the caller named - while this answers "what is in this group", which is a different question with a different shape
 * (a filter, an order, a page). {@code JooqOpenObligations} is the same kind of port for the preview's question, and
 * neither is a repository: what leaves is a port/in type, so no row type crosses out of the adapter (RULE B16).
 *
 * <p><strong>The status is derived, and derived once.</strong> Which of the contract's four states an obligation is
 * in depends on this module's {@code voided_at} and on the settlement worker's runs, so the derivation lives in one
 * place and both this read and the replay path use it ({@code ObligationStatuses}). Two copies would be two answers
 * to the same question, which is how a list and a form end up disagreeing about whether a commitment is still owed.
 */
public interface Obligations {

    /**
     * The group's obligations, narrowed and ordered as the filter says.
     *
     * <p>Ordered by {@code (incurred_at, id)} so the order is total: two commitments recorded in the same
     * millisecond - a seeded group, or a test - still have one order rather than whichever the rows came back in, and
     * a page boundary that moved between two reads would show a row twice or not at all.
     */
    List<RecordedObligation> listFor(ObligationFilter filter);
}
