package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Somebody handing in a code, and the person the session says they are.
 *
 * <p><strong>The identity gap this record documented is closed at the boundary, and the use case did not change.</strong>
 * Its `userId` used to be supplied by the request body, with a note explaining that there was no `GET /me` to derive one
 * from - and the contract promised that "when sign-in exists, this body disappears and the acceptance becomes about the
 * caller". Sign-in exists (ADR 0042), so the boundary resolves the session and hands the id down: the field now records
 * a fact about the request rather than an assertion inside it, and `AcceptInviteService` is byte-for-byte what it was,
 * which is what it looks like when the gap was one layer out.
 *
 * <p>{@code displayName} is what the group will call them, so accepting a code is a way of *becoming a member* and
 * refuses a name by the rule the members write uses ({@link MemberRefusals.NameTooLong}) rather than a second rule of its
 * own. The role is not here: accepting joins as `member`, which the operation's description says, because an invitation
 * that conferred `admin` would be a privilege handed out by a default.
 *
 * @param code        the code from the invitation
 * @param userId      who is joining, resolved from the caller's session at the boundary (ADR 0047)
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
