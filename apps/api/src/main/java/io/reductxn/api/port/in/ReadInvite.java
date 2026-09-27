package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.time.Instant;

/**
 * The use case: what an invite code is for, before anybody accepts it.
 *
 * <p><strong>It is the read that makes a code the thing a group shares</strong> (ADR 0054). A code could be created
 * and spent and never read, so the person following a link had to type their name into an operation that named no
 * group - and a group with something to share had nothing to send but a group id, which is exactly the artifact that
 * slice took out of everybody's hands. This is a different question from the acceptance's: that one asks "join me",
 * this one answers "what would I be joining".
 *
 * <p><strong>No caller, and that is the operation rather than an omission.</strong> Every other read in this module
 * resolves a session, because every other read is a group's books and membership is the condition. This one is asked
 * by somebody who has not signed in yet - the person the link was sent to - and the code is the capability they were
 * given: eight characters from a thirty-two-symbol alphabet, with a window and optionally a use limit, which is
 * already the only credential this product hands out (ADR 0041). Requiring a session here would mean asking somebody
 * to create an account to find out what they are being asked to join.
 *
 * <p>{@code at} is supplied rather than read from a clock, for the reason every other use case here states: a use case
 * that read a clock could not be tested at the instant its answer changes - and this one's answer changes exactly at
 * its window's edge, where "expired" replaces "usable".
 */
public interface ReadInvite {

    /**
     * The invitation behind a code, and the group it joins somebody to.
     *
     * @param at the instant to judge the code's window and its uses at; supplied rather than read from a clock
     * @return the preview, or {@link InviteRefusals.NoSuchInvite} / {@link InviteRefusals.InviteExpired} /
     *     {@link InviteRefusals.InviteExhausted} - the same three conditions the acceptance refuses, decided the same
     *     way, so a person is told the code has run out before they type their name rather than after
     */
    Result<InvitePreview> preview(String code, Instant at);
}