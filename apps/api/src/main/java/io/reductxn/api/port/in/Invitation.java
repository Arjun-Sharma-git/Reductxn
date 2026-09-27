package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An invitation as stored: what it grants, which group it joins or shows, when it stops, how many times it may be spent,
 * and how often it has.
 *
 * <p><strong>The window and the limit are the two rules an acceptance applies, and neither is a boolean column.</strong>
 * An invitation is usable when the instant it is being used at is before its expiry *and* its uses are within its limit
 * - which is why this record carries the four facts rather than a `usable` flag: a flag would be a judgement made when
 * the row was written, and an invitation's usability is a fact about *when* somebody asks. {@code maxUses} is null for
 * an invitation with no limit, which is the contract's optional field with its absent value rather than a zero that
 * means "unlimited" (a number the table refuses, for exactly that reason).
 *
 * <p>{@code uses} is here as well as in the store because the acceptance decides between "expired" and "exhausted" -
 * two codes under one status, so a reader can be told which one ran out (ADR 0041).
 *
 * <p><strong>{@code grants} is what decides which operation can use this row (ADR 0055).</strong> An acceptance spends
 * a {@code MEMBERSHIP} code and refuses a {@code READ} one; the shared read serves a {@code READ} code and refuses
 * anything else. Both are done from this field rather than from which endpoint the row came from, because the row is
 * what a code *is* and the endpoint is only how somebody got here.
 *
 * @param grants    what holding the code entitles somebody to
 * @param groupId   the group the code joins somebody to, or shows somebody
 * @param expiresAt when the window closes; a use at or after this instant is refused
 * @param maxUses   how many times it may be spent, or null for no limit
 * @param uses      how many times it has been spent
 */
public record Invitation(InvitationGrant grants, UUID groupId, Instant expiresAt, Integer maxUses, int uses) {

    public Invitation {
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
