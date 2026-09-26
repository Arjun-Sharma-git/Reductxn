package io.reductxn.api.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The sessions: one row per sign-in, holding both tokens hashed.
 *
 * <p><strong>Nothing here takes or returns a token.</strong> The caller hashes, looks up by hash and hands over hashes,
 * because a hash is what the table holds and a read that took the token itself would make the store a place where a
 * credential exists. It also keeps the hashing rule in one layer: {@code TokenHash} is where "a 256-bit random value is
 * hashed with SHA-256, not with something slow" is written down once.
 *
 * <p><strong>The rotation is one conditional update, and it is the reason this is a boolean.</strong> A refresh finds the
 * row by the old refresh token and rewrites it with both new hashes; if the old token does not match, nothing is updated.
 * Two rotations racing on one token therefore produce one winning pair and one refusal, rather than two live pairs, and
 * the loser cannot be handed tokens for a session it did not renew. The same shape as the invitation's spend, and for the
 * same reason: the condition has to be evaluated by the database, at the moment of the write.
 */
public interface Sessions {

    /** Records a sign-in. */
    void create(NewSession session);

    /**
     * The session behind an access token, whatever state it is in.
     *
     * <p>Expiry is the caller's to judge: a store that filtered by time would make "this session ended" and "this token
     * was never issued" one answer, and the refusal keeps them one answer deliberately - but the read that decides must
     * still be able to say "known, but over" if something ever needs to.
     */
    Optional<AuthenticatedSession> findByAccessTokenHash(String accessTokenHash);

    /**
     * Replaces both tokens of the session behind a refresh token, without moving the session's end.
     *
     * <p>The condition is the whole rotation: the row is found by the old refresh token *and* a window that has not
     * closed, and only the access half's expiry is rewritten. So the session's end is carried over by not being touched,
     * which is the only way a rotation cannot extend it - and a replay of the old refresh token finds no row rather than
     * producing a second pair.
     *
     * @return true when the old refresh token named a live session and the pair was replaced, false when it did not -
     *     expired, rotated away, or never issued
     */
    boolean rotate(String refreshTokenHash, RotatedTokens replacement);

    /** A session to record. */
    record NewSession(String accessTokenHash, String refreshTokenHash, UUID userId, String deviceId, Instant createdAt,
            Instant accessExpiresAt, Instant refreshExpiresAt) {
    }

    /**
     * The session behind an access token.
     *
     * @param userId           whose session it is
     * @param accessExpiresAt  when the access token stops working
     * @param refreshExpiresAt when the refresh token stops working - the session's real end, since a refresh renews the
     *     access half but not this
     */
    record AuthenticatedSession(UUID userId, Instant accessExpiresAt, Instant refreshExpiresAt) {
    }

    /**
     * The new pair and the instant it was drawn, for the row a rotation leaves behind.
     *
     * @param now the rotation's instant, which is what the refresh window is compared against - the row's own
     *     {@code refresh_expires_at} is left where it is
     */
    record RotatedTokens(String accessTokenHash, String refreshTokenHash, Instant accessExpiresAt, Instant now) {
    }
}
