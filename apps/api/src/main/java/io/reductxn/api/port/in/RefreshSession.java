package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: renew a session, replacing both tokens.
 *
 * <p><strong>The contract had no way to say which token.</strong> `POST /auth/refresh` declares `security: []` - which is
 * how it says "this is not authenticated by the bearer token" - and it declares no request body, so there was nothing
 * anywhere that could carry the refresh token. This slice gave it one ({@code RefreshRequest} in the schema), and the
 * alternative was to read the bearer header and interpret it as a refresh token, which would make one header mean two
 * things depending on the path (ADR 0042).
 *
 * <p><strong>Both tokens are replaced, always.</strong> Keeping the refresh token while renewing the access token would
 * leave a credential valid for the rest of its life, which is the opposite of what rotation is for. The rotation is a
 * single conditional update - the row is found by the old refresh token and rewritten - so two rotations racing on one
 * token resolve to one winner rather than to two live pairs.
 */
public interface RefreshSession {

    /** @return a new pair, or {@link SignInRefusals.NotSignedIn} when this refresh token is not a live session */
    Result<IssuedTokens> refresh(RefreshRequest request);
}
