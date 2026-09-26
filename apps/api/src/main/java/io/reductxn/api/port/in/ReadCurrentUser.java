package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: who is asking.
 *
 * <p>`GET /me` has been in the contract since the first slice, and the coverage check recorded it as the gap that made
 * everything else look plausible: without it the client falls back to showing a uuid, and the missing members read
 * looked deliberate for seven slices (ADR 0038). It is also the read that makes a token mean something - until this
 * operation exists, a token is a string that nothing checks.
 *
 * <p><strong>The token is the whole input.</strong> There is no user id in the path, no query and no body: the caller says
 * who they are by holding a token, which is the one thing a boundary cannot fake. Expiry is judged here rather than in the
 * store, because it is a rule about sessions rather than a property of a row - the store answers with the row and when it
 * ends, and this decides whether that is still a session.
 */
public interface ReadCurrentUser {

    /**
     * Reads the person a token speaks for.
     *
     * @return the user, or {@link SignInRefusals.NotSignedIn} when the token is unknown or its session has ended
     */
    Result<SignedInUser> read(String accessToken);
}
