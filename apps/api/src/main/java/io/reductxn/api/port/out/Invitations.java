package io.reductxn.api.port.out;

import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.Invitation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitation codes: making them, finding them by their code, and spending one.
 *
 * <p><strong>Three methods, and the third is the reason the other two are not enough.</strong> Spending a use has to be
 * conditional - an invitation is usable only while it is unexpired and within its limit - and the condition has to be
 * evaluated *by the database*, because two acceptances can arrive at the same instant and only one of them can take the
 * last use. A read-then-write in this layer would be the check-then-act that loses exactly that race, so the condition
 * travels into the statement and the answer comes back as a boolean: did this call spend a use.
 *
 * <p><strong>Why {@link #record} decides the window's values and the caller computes them.</strong> The service owns
 * the policy - how long an invitation lasts, and how many times one may be spent - because that is a rule about
 * invitations rather than about storage. What the store owns is the code: it is the primary key, so generating a
 * colliding one is a storage concern that the store settles by retrying.
 *
 * <p>There is deliberately no method to list a group's invitations. The contract has no such operation, and the activity
 * read - still recorded as a gap - is the one that would want it. A port method nobody calls is a read nobody needs and
 * an index nobody pays for (see `V105__invitations.sql`).
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
     * Spends one use of an unexpired invitation, if it has one left.
     *
     * @return true when this call took a use, false when the invitation is expired or exhausted - the caller cannot tell
     *     which from here, and decides the difference from the row it read before calling
     */
    boolean spend(String code, Instant now);
}
