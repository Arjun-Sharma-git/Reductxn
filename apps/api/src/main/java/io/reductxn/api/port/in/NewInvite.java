package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A request for a code that lets somebody join a group.
 *
 * <p><strong>Two fields, because the contract gives the operation no body.</strong> `POST /groups/{groupId}/invites`
 * declares no request schema at all, so there is nowhere for a caller to say how long the invite should last or how
 * many times it may be spent - which means the deployment decides, and that is a decision this slice made rather than a
 * gap it left (ADR 0041). What a caller supplies is the group, and what the boundary supplies is "now" (RULE B21): the
 * window and the limit are the service's, so a test can pin the instant and get a reproducible expiry.
 *
 * @param groupId   the group the code joins somebody to
 * @param createdAt when the code was made, from the boundary's clock - which is also the window's start
 */
public record NewInvite(UUID groupId, Instant createdAt) {

    public NewInvite {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
