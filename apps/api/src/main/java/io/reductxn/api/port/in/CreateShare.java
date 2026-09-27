package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: make a share link, if the caller is in the group it shows.
 *
 * <p><strong>A separate use case from {@link CreateInvite}, with one word of difference and one reason.</strong> The
 * policy is the same policy - a member of the group, the same window, the same absence of a limit - and it lives in one
 * place ({@code InvitationPolicy}) precisely because both operations apply it. What differs is what the code it writes
 * grants, and that difference is not a parameter of the other operation: an invitation is a link a group sends to
 * somebody it is about to have as a member, and a share link is what a group gives somebody who will never be one.
 * Putting the choice in a body would have made "join somebody" a default a caller could forget to change (ADR 0055).
 *
 * <p>The membership check is {@code Groups.isMember} and the refusal is {@code GroupRefusals.NoSuchGroup}, the same pair
 * every other write to a group's record uses since ADR 0048 - a link is something a group hands out, and a group
 * somebody is not in is not theirs to hand anything out for.
 */
public interface CreateShare {

    /**
     * A code that shows whoever holds it what this group owes whom.
     *
     * @return the code and its window, or the reason the caller cannot make one
     */
    Result<CreatedInvite> create(UUID callerId, NewShare share);
}