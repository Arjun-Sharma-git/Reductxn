package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to settle a group's commitments as of an instant.
 *
 * <p><strong>The instant is the caller's, not the clock's.</strong> It keys the cycle, it is what every rate
 * and tax rule is resolved at, and it is what makes the run reproducible: a run over a past cycle has to be
 * the run the group was shown then, not a fresh answer computed from today's data. A worker that read the clock
 * itself could not be asked that question at all, and could not be tested without one.
 *
 * @param groupId the group whose cycle this is
 * @param asOf    the instant to cut the cycle off at
 */
public record RunRequest(UUID groupId, Instant asOf) {

    public RunRequest {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");
    }
}
