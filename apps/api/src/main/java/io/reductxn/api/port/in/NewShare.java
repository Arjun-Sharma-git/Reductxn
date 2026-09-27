package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A request for a code that lets somebody look at a group without joining it (ADR 0055).
 *
 * <p><strong>The same two fields {@link NewInvite} has, and the same reason for each.</strong>
 * `POST /groups/{groupId}/shares` declares no request body either, so the window and the limit are the deployment's
 * rather than a caller's, and the boundary supplies "now" so a test can pin the instant and get a reproducible expiry.
 * What differs from an invitation is not a field but what the service writes: {@link InvitationGrant#READ}.
 *
 * <p>It is a separate record rather than a {@code NewInvite} with a grant argument, because the two are two commands:
 * one is "let somebody in" and the other is "let somebody look", and a single type would let a caller pass the wrong
 * word to the wrong operation. Two types, two services, one policy.
 *
 * @param groupId   the group the link shows
 * @param createdAt when the code was made, from the boundary's clock - which is also the window's start
 */
public record NewShare(UUID groupId, Instant createdAt) {

    public NewShare {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}