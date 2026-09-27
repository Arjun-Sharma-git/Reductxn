package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.NewSchedule;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's `CycleScheduleRequest` schema: a group stating when its books close (ADR 0057).
 *
 * <p><strong>Both fields are boxed, and the difference from the port's {@code int} is a refusal.</strong> A missing field
 * deserialises to null, and the handler answers {@code api.request_incomplete} for it rather than letting an absent
 * `everyDays` become a zero - which is a value the contract refuses as the "on every pass" cadence nobody means - or an
 * absent instant become 1970, which would be a refusal about the *past* rather than about the request. Two absences with
 * two codes, so a caller is told to change the right thing.
 */
public record CycleScheduleRequest(Integer everyDays, Instant nextDueAt) {

    /** True when the body says both things the operation needs. */
    public boolean isComplete() {
        return everyDays != null && nextDueAt != null;
    }

    /** The command this body is, with the boundary's instant as the stamp (RULE B21). */
    public NewSchedule toCommand(UUID groupId, Instant setAt) {
        return new NewSchedule(groupId, everyDays, nextDueAt, setAt);
    }
}