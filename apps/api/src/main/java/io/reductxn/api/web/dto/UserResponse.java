package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.SignedInUser;

import java.util.UUID;

/**
 * The contract's `User`: who this deployment thinks is asking.
 *
 * <p><strong>{@code kycTier} is always {@code none}, and the constant is here because that is a fact about the
 * deployment.</strong> There is no KYC provider, so no tier has been established for anybody; the schema requires the
 * field, and the operation's description says the value means "not established" rather than "established as nothing". The
 * alternative - storing a tier column that nothing ever writes - would be a column that reads as a decision somebody made.
 *
 * <p>{@code displayName} is the deployment's name for them, which in this build is their phone number: a name nobody
 * chose, and the group's own name for a member is the one a person sees
 * ({@code group_members.display_name}).
 */
public record UserResponse(UUID id, String displayName, String kycTier) {

    /** What a KYC tier means when no provider has established one. */
    static final String NO_TIER_ESTABLISHED = "none";

    public static UserResponse of(SignedInUser user) {
        return new UserResponse(user.id(), user.displayName(), NO_TIER_ESTABLISHED);
    }
}
