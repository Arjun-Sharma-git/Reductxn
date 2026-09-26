package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: a code came back, so sign the person in.
 *
 * <p><strong>This is where a user is created, and that is what an OTP build means.</strong> There is no separate
 * sign-up: the code proves the number, and a number nobody has seen before becomes a user in the same statement that
 * would have found it. The find-or-create is the store's, because the two halves of it have to settle a race - two
 * verifications of one number at the same instant are one account, and the unique constraint is what makes that true
 * rather than a check that reads before it writes.
 *
 * <p>The tokens are opaque and are returned once. Nothing stores the token itself: the session holds hashes, so a leaked
 * database is not a set of working sessions ({@code V106__identity.sql} says why the access token is hashed with SHA-256
 * rather than something slow).
 */
public interface VerifySignIn {

    /**
     * Spends the challenge and issues a session.
     *
     * @return a token pair, or the refusal - which never says which part of the code was wrong (see
     *     {@link SignInRefusals.OtpNotAccepted})
     */
    Result<IssuedTokens> verify(SignInCode code);
}
