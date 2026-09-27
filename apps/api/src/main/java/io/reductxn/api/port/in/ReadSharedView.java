package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.time.Instant;

/**
 * The use case: what a share link shows, to whoever holds it.
 *
 * <p><strong>No caller, and that is the operation rather than an omission.</strong> Every other read of a group's books
 * resolves a session and asks for a membership (ADR 0054); this one is asked by somebody a member handed a code to, and
 * the code is the whole credential - the same shape {@link ReadInvite} has, for the same reason. What differs between
 * the two is what the code entitles somebody to: a preview says *which* group a code is for, and this says what that
 * group owes whom.
 *
 * <p><strong>It is the balances read with a code instead of a membership.</strong> The positions come from the same
 * composition ({@code GroupPositions}) that {@link ReadGroupBalances} uses - one implementation, two conditions - and
 * the names come from the group's own membership rows, because the person reading a share link cannot call the members
 * read. What it deliberately does not show is recorded on {@link SharedView}.
 *
 * <p>{@code at} is supplied rather than read from a clock, for the reason every use case here states - and this is the
 * read where it matters most to a *reader*: a link can be opened days after it was made, so the answer carries the
 * instant it was computed at rather than leaving somebody to assume it is their own.
 */
public interface ReadSharedView {

    /**
     * The group and its positions, if the code is a share link that still works.
     *
     * @param at the instant to judge the code's window and to compute the positions at
     * @return the view, or {@link InviteRefusals.NoSuchInvite} / {@link InviteRefusals.NotAShare} /
     *     {@link InviteRefusals.InviteExpired} / {@link InviteRefusals.InviteExhausted}
     */
    Result<SharedView> read(String code, Instant at);
}