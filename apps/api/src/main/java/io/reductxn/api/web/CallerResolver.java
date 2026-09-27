package io.reductxn.api.web;

import io.reductxn.api.port.in.ReadCurrentUser;
import io.reductxn.api.port.in.SignInRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Who is making this request, resolved once for every boundary that needs to know.
 *
 * <p><strong>It exists because three operations now ask the same question, and the answer must be the same one.</strong>
 * `GET /me` reads the token through {@link ReadCurrentUser}; the three group operations need a caller as well, and each of
 * them could have re-implemented "read the header, look the session up, decide what a refusal looks like" - three copies of
 * a rule about credentials, each of which would one day disagree about a case like a header with the wrong scheme. The
 * controller still decides the <em>status</em>, because that is a boundary's job; what is shared is who the caller is.
 *
 * <p><strong>A missing header and an unknown token are one answer.</strong> Both are
 * {@link SignInRefusals.NotSignedIn}, and the sameness is deliberate: from the outside they are the same request, and
 * telling them apart would tell a caller whether a token was ever real (ADR 0042). What this resolver adds is that the
 * refusal is a value rather than an exception, so a boundary maps it exactly as it maps every other expected failure.
 */
@Component
public class CallerResolver {

    private final ReadCurrentUser readCurrentUser;

    public CallerResolver(ReadCurrentUser readCurrentUser) {
        this.readCurrentUser = Objects.requireNonNull(readCurrentUser, "readCurrentUser");
    }

    /**
     * The person this request speaks for.
     *
     * @return the caller, or {@link SignInRefusals.NotSignedIn} when the request carries no usable session
     */
    public Result<SignedInUser> resolve(HttpServletRequest request) {
        Optional<String> token = BearerToken.from(request);

        if (token.isEmpty()) {
            return Result.fail(new SignInRefusals.NotSignedIn());
        }

        return readCurrentUser.read(token.get());
    }
}
