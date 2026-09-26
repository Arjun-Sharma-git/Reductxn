package io.reductxn.api.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The one-time codes, and the count that keeps them from being free messages.
 *
 * <p><strong>The hash and the salt are the store's to keep and the service's to compute.</strong> The service derives
 * them (see {@code OneTimeCodeHash}) and hands them over; what the store must never do is see the code itself, and what
 * it must always return is the pair, because verification needs both. A method that took a phone number and a code and
 * answered yes or no would put the comparison in the adapter, where the rule "a code is good for a few minutes and a few
 * attempts" would have to be written a second time.
 *
 * <p>{@code countRequestedSince} is the rate limit, and it counts rows rather than a counter column on purpose: a counter
 * needs a window to reset, and a window that resets is a window an attacker waits out. Counting the challenges that
 * actually exist since an instant makes the limit a fact about what happened, and it is what the index on
 * {@code (phone_number, requested_at DESC)} is for.
 */
public interface OtpChallenges {

    /** Records a challenge, so the code it hashes can be verified later. */
    void record(NewChallenge challenge);

    /**
     * The newest challenge for this number that has not been spent, whether or not it has expired.
     *
     * <p>Expiry is the service's to judge, because it is a rule rather than a lookup - and a store that filtered by time
     * would make "the code expired" indistinguishable from "no code was requested", which the refusal deliberately keeps
     * the same (see {@code SignInRefusals.OtpNotAccepted}).
     */
    Optional<StoredChallenge> newestUnspent(String phoneNumber);

    /** Marks a challenge spent, so the same code cannot sign anybody in twice. */
    void spend(UUID id, Instant at);

    /** Records a failed attempt, so a code that is guessed at dies. */
    void registerAttempt(UUID id);

    /** How many codes this number has asked for since an instant. The rate limit's whole input. */
    int countRequestedSince(String phoneNumber, Instant since);

    /** A challenge to record: what the code hashes to, with which salt, and how long it lives. */
    record NewChallenge(UUID id, String phoneNumber, byte[] codeHash, byte[] salt, Instant requestedAt, Instant expiresAt) {
    }

    /**
     * A stored challenge.
     *
     * @param id        which challenge, so an attempt or a spend can name it
     * @param codeHash  what the code hashes to
     * @param salt      the salt that hash used
     * @param attempts  how many failed tries this challenge has had
     * @param expiresAt when it stops being usable
     */
    record StoredChallenge(UUID id, byte[] codeHash, byte[] salt, int attempts, Instant expiresAt) {
    }
}
