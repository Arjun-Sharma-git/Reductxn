package io.reductxn.api.port.in;

import io.reductxn.planning.api.SettlementPlan;

import java.time.Instant;
import java.util.UUID;

/**
 * A what-if preview of settling a group, with nothing committed.
 *
 * <p>The inbound port: {@code web} depends on this interface and never on the implementation, which is
 * what keeps a controller unable to reach a repository (RULE B12).
 *
 * <p>Returns the plan itself rather than an API-shaped object. The plan is the domain answer - per
 * currency, since a group may hold more than one - and the shape the contract wants is a view of it.
 * Mapping inside the use case would put a wire format in the application layer, where it would then be
 * the thing that has to change when the contract does.
 */
@FunctionalInterface
public interface PreviewNetting {

    /**
     * Plans the group's open obligations as of an instant, without recording anything.
     *
     * @param groupId the group to preview
     * @param asOf    the instant to assess at; supplied rather than read from a clock, so a past preview
     *                stays reproducible
     * @return one plan per currency present in the group's open obligations
     */
    SettlementPlan preview(UUID groupId, Instant asOf);
}
