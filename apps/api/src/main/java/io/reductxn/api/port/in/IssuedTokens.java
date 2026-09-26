package io.reductxn.api.port.in;

import java.util.Objects;

/**
 * The tokens a sign-in produced, as the contract's `TokenPair` describes them.
 *
 * <p><strong>The tokens themselves travel once and are never stored.</strong> Both are returned to the caller who asked;
 * the session row holds their hashes. That is why this value exists at all rather than the service handing back a session
 * row: the row cannot produce a token, and a caller needs the ones that were issued for them.
 *
 * <p>{@code expiresIn} is seconds, which is what the contract says, and it is derived from the session's expiry rather
 * than being a second constant that could disagree with it.
 *
 * @param accessToken  the token for authenticated reads, valid for {@code expiresIn} seconds
 * @param refreshToken the token that renews the pair, valid for longer
 * @param expiresIn    how many seconds the access token has left, counted from when it was issued
 */
public record IssuedTokens(String accessToken, String refreshToken, long expiresIn) {

    public IssuedTokens {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshToken, "refreshToken");

        if (expiresIn <= 0) {
            throw new IllegalArgumentException("expiresIn must be positive, and " + expiresIn + " is not. A token pair "
                    + "that is already expired is not something to hand a caller");
        }
    }
}
