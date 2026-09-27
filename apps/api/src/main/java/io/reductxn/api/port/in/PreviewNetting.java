package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;
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
 *
 * <p><strong>A {@code Result} since ADR 0054.</strong> The caller travels beside the group and a group they are not
 * in answers {@link GroupRefusals.NoSuchGroup} - a preview is the plan a group is about to execute, so it is not a
 * read that could be public. This is the second of the two reads the client's paste box reached directly, which is
 * why the box now takes a code rather than an id.
 */
@FunctionalInterface
public interface PreviewNetting {

    /**
     * Plans the group's open obligations as of an instant, without recording anything.
     *
     * @param groupId the group to preview
     * @param asOf    the instant to assess at; supplied rather than read from a clock, so a past preview
     *                stays reproducible
     * @return one plan per currency present in the group's open obligations, or the reason it could not be planned
     */
    Result<SettlementPlan> preview(UUID callerId, UUID groupId, Instant asOf);
}
