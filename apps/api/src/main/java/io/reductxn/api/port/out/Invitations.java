package io.reductxn.api.port.out;

import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.Invitation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitation codes: making them, finding them by their code, reading what one is for, and spending one.
 *
 * <p><strong>Four methods, and the fourth is the reason the other three are not enough.</strong> Spending a use has to
 * be conditional - an invitation is usable only while it is unexpired and within its limit - and the condition has to be
 * evaluated *by the database*, because two acceptances can arrive at the same instant and only one of them can take the
 * last use. A read-then-write in this layer would be the check-then-act that loses exactly that race, so the condition
 * travels into the statement and the answer comes back as a boolean: did this call spend a use.
 *
 * <p><strong>Why {@link #record} decides the window's values and the caller computes them.</strong> The service owns
 * the policy - how long an invitation lasts, and how many times one may be spent - because that is a rule about
 * invitations rather than about storage. What the store owns is the code: it is the primary key, so generating a
 * colliding one is a storage concern that the store settles by retrying.
 *
 * <p><strong>{@link #previewOf} arrived with the read that makes a code shareable</strong> (ADR 0054), and it is the
 * one method here that reads a second table. That is a deliberate exception to this port's own shape rather than an
 * accident of convenience: the group a code joins is not the `groups` read's business before somebody has joined, and
 * "which group is this code for" is a question about the code.
 *
 * <p>There is still deliberately no method to list a group's invitations. The contract has no such operation, and a
 * member can read the codes they were given rather than the ones a group has made. A port method nobody calls is a read
 * nobody needs and an index nobody pays for (see `V105__invitations.sql`).
 */
public interface Invitations {

    /**
     * Records an invitation and answers with the code it can be spent by.
     *
     * <p>The window's two bounds and the limit are the caller's, because they are the caller's policy. The code is not:
     * see the class comment.
     */
    CreatedInvite record(UUID groupId, Instant createdAt, Instant expiresAt, Integer maxUses);

    /** The invitation with this code, or empty when no invitation has it. */
    Optional<Invitation> findByCode(String code);

    /**
     * The invitation with this code **and the group it joins somebody to**, or empty when no invitation has it.
     *
     * <p><strong>One statement rather than two, because the group's label is not this module's `groups` read.</strong>
     * {@link io.reductxn.api.port.out.Groups} deliberately answers nothing without a membership, so the name and the
     * member count of a group nobody has joined yet cannot come from there - and it should not be able to: a port whose
     * every method narrows by membership is only as trustworthy as its narrowest method. The question here is not "what
     * is this group" but "what does this code join me to", and the code keys both tables, so it is one select.
     *
     * <p>The row is the same one {@link #findByCode} returns, plus the label: {@code uses} is carried because the
     * use case decides "expired" from "exhausted" with it, and the caller cannot make that decision from the answer's
     * terms alone. The label's `name` is null for a group that exists only as rows, which is a state this build
     * contains (ADR 0043) - the member count is counted from memberships and is always known.
     */
    Optional<Preview> previewOf(String code);

    /**
     * Spends one use of an unexpired invitation, if it has one left.
     *
     * @return true when this call took a use, false when the invitation is expired or exhausted - the caller cannot tell
     *     which from here, and decides the difference from the row it read before calling
     */
    boolean spend(String code, Instant now);

    /**
     * An invitation and the group it joins, as the read that decides whether it still works needs both.
     *
     * <p>It is a store record rather than the answer, and the difference is the two fields the answer does not carry:
     * {@code uses} and {@code groupId}. The use case needs {@code uses} to choose between "expired" and "exhausted" -
     * two codes under one status, so a reader is told which ran out (ADR 0041) - and it needs the group's id because the
     * preview answers with it: a person who has just joined has no other way to reach the group they joined.
     *
     * @param groupName the group's name, or null for a group that exists only as rows (ADR 0043)
     * @param memberCount how many memberships the group has, counted in the same statement
     */
    record Preview(UUID groupId, String groupName, int memberCount, Instant expiresAt, Integer maxUses, int uses) {

        public Preview {
            java.util.Objects.requireNonNull(groupId, "groupId");
            java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
