package io.reductxn.api.application.identity;

import io.reductxn.api.port.in.IssuedTokens;
import io.reductxn.api.port.in.RefreshRequest;
import io.reductxn.api.port.in.RefreshSession;
import io.reductxn.api.port.in.SignInRefusals;
import io.reductxn.api.port.out.Sessions;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * The use case: renew a session, replacing both tokens.
 *
 * <p><strong>No {@code @Transactional}</strong>, and it is the opposite of the verify use case: renewal is one
 * conditional update, and its own {@code WHERE} clause is what settles the race between two rotations of one token.
 * Wrapping a single statement in a transaction would add nothing and would claim an isolation this deployment does not
 * have (RULE B15).
 *
 * <p><strong>Both tokens are replaced by that same statement.</strong> A rotation that issued an access token and kept
 * the refresh token would leave a credential live for the rest of its life, and the conditional update is also what makes
 * a replay of the previous refresh token a {@code 401} rather than a second pair: the old hash no longer names a row.
 *
 * <p><strong>What it does not do is extend the session.</strong> The refresh window is carried over untouched, so a
 * client that has been refreshing for twenty-nine days is signed out on the thirtieth - which is what a session length
 * means, and the alternative (a renewing window) is a session that never ends as long as somebody keeps using it.
 */
@Service
public class RefreshSessionService implements RefreshSession {

    private final Sessions sessions;

    public RefreshSessionService(Sessions sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public Result<IssuedTokens> refresh(RefreshRequest request) {
        Objects.requireNonNull(request, "request");

        Instant at = request.refreshedAt();

        String accessToken = SessionTokens.issue();
        String replacementRefreshToken = SessionTokens.issue();

        boolean rotated = sessions.rotate(
                SessionTokens.hash(request.refreshToken()),
                new Sessions.RotatedTokens(
                        SessionTokens.hash(accessToken),
                        SessionTokens.hash(replacementRefreshToken),
                        at.plus(VerifySignInService.ACCESS_LIFETIME),
                        at));

        if (!rotated) {
            return Result.fail(new SignInRefusals.NotSignedIn());
        }

        return Result.ok(new IssuedTokens(accessToken, replacementRefreshToken,
                VerifySignInService.ACCESS_LIFETIME.toSeconds()));
    }
}
