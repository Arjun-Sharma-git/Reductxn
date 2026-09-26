package io.reductxn.api.port.in;

import io.reductxn.netting.api.NetPosition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The use case: what each member owes or is owed, as of an instant.
 *
 * <p>It answers {@link NetPosition} - the netting module's own type - rather than a record of its own. This module
 * already uses that type wherever a member's position travels (the preview response is built from the plan's), and a
 * second shape with the same two fields would be a second thing to keep in step with the first, for nothing. The
 * wire format is a different question, and it is answered in the web layer (ADRs 0037, 0039).
 *
 * <p><strong>It takes an instant rather than reading the clock</strong>, like the preview's port does. The balance
 * endpoint has no {@code asOf} parameter - a balance is "now" - but the boundary that decides what "now" means is
 * the controller, not this: a use case that read a clock could not be tested without one, and its answer would
 * change between two calls in the same request.
 *
 * <p><strong>One entry per member per currency</strong>, and a member holding two currencies appears twice. That is
 * not this port's decision to make - it falls out of the plan, whose partitions are per currency because ADR 0012
 * refuses a cross-currency total - but it is the shape a caller will see, so it is stated here.
 */
public interface ReadGroupBalances {

    /**
     * Every position in this group's open obligations at {@code asOf}, one per member per currency.
     *
     * @return the positions, ordered as the plan orders them: by currency, then as the engine emitted them
     */
    List<NetPosition> balances(UUID groupId, Instant asOf);
}
