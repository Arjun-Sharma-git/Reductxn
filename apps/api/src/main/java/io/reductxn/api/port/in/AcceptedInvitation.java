package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Somebody handing in a code and saying who they are.
 *
 * <p><strong>The body is the identity, and that is the recorded gap rather than a design.</strong> There is no `GET /me`
 * and no authentication in this build, so the server cannot know who is holding the code - the same absence the
 * decision endpoint works with, where "the member is whoever the body says" (ADR 0020), and the same one the members
 * write works with. The alternative was to derive the member from something the request already carries, which would
 * mean inventing an identity and then trusting it; naming who is joining at least makes the trust explicit, and the gap
 * is recorded until sign-in exists (ADR 0041).
 *
 * <p>{@code displayName} is what the group will call them, so accepting a code is a way of *becoming a member* and
 * refuses a name by the rule the members write uses ({@link MemberRefusals.NameTooLong}) rather than a second rule of its
 * own. The role is not here: accepting joins as `member`, which the operation's description says, because an invitation
 * that conferred `admin` would be a privilege handed out by a default.
 *
 * @param code        the code from the invitation
 * @param userId      who is joining, as the body says (see the identity gap above)
 * @param displayName what the group will call them
 * @param acceptedAt  when they handed it in, from the boundary's clock
 */
public record AcceptedInvitation(String code, UUID userId, String displayName, Instant acceptedAt) {

    public AcceptedInvitation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
