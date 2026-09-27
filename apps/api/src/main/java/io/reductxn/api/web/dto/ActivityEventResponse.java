package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.reductxn.api.port.in.ActivityEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's `ActivityEvent`, as served.
 *
 * <p><strong>{@code NON_NULL}, and this is the response that needs it most.</strong> Every optional field here means
 * something by its absence: a `displayName` missing means the row does not record what the group calls the person (a
 * `settlement_decisions` row), a `maxUses` missing means the code is unbounded, and a `memberId` missing means nobody is
 * named. A `null` in the payload would flatten those three into "there is a value and it is nothing", and a client would have
 * to know which kind it was looking at to tell them apart - which is exactly what the optional-fields shape is meant to
 * avoid.
 *
 * <p>{@code kind} is written by an exhaustive switch over the enum rather than from its name: a rename inside the module
 * should break the build, not silently change a field a client branches on (the reason `ModuleBoundaryTest`'s B20 note gives
 * for the same pattern elsewhere).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivityEventResponse(
        String kind,
        Instant at,
        UUID memberId,
        String displayName,
        String role,
        String decision,
        Integer uses,
        Integer maxUses) {

    public static ActivityEventResponse of(ActivityEvent event) {
        return new ActivityEventResponse(
                switch (event.kind()) {
                    case MEMBER_JOINED -> "member_joined";
                    case DECISION_RECORDED -> "decision_recorded";
                    case INVITATION_CREATED -> "invitation_created";
                },
                event.at(),
                event.memberId(),
                event.displayName(),
                event.role(),
                event.decision(),
                event.uses(),
                event.maxUses());
    }
}
