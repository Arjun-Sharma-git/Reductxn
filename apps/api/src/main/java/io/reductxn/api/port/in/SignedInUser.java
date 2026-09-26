package io.reductxn.api.port.in;

import java.util.Objects;
import java.util.UUID;

/**
 * The person a session belongs to, as identity knows them.
 *
 * <p><strong>No KYC tier, and the absence is deliberate.</strong> The contract's `User` requires `kycTier`, and this
 * deployment has no KYC provider: the answer is `none` for everybody, which is a fact about the deployment rather than a
 * fact about the person, so it belongs at the boundary that decides what to serve rather than in a value that claims to
 * describe somebody (ADR 0042).
 *
 * <p>{@code displayName} is what the product calls them - not what a group calls them, which is
 * {@code group_members.display_name}. The two can differ, and neither is a correction of the other.
 *
 * @param id          the user id, which is the uuid other boundaries will compare against
 * @param displayName what this deployment calls them before any group does
 */
public record SignedInUser(UUID id, String displayName) {

    public SignedInUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
    }
}
