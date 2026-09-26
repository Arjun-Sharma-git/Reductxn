package io.reductxn.api.application.identity;

import io.reductxn.api.port.in.ReadCurrentUser;
import io.reductxn.api.port.in.SignInRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.port.out.Sessions;
import io.reductxn.api.port.out.UserAccounts;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * The use case: who is asking.
 *
 * <p><strong>No {@code @Transactional}</strong>: two reads whose disagreement cannot happen in this build. There is no
 * operation that deletes a user or ends a session, so a session whose account has gone is not a state anything can reach
 * - and a transaction over two reads would be an isolation claim about a race that does not exist (RULE B15).
 *
 * <p><strong>Expiry is judged here, not in the store.</strong> The session read answers with the row and its windows, and
 * this decides whether that is still a session - because "a token is valid for thirty minutes" is a rule about tokens,
 * and a store that filtered by time would make "this session ended" and "this token was never issued" distinguishable
 * only by a second query.
 *
 * <p>The clock is this use case's, unlike every other read in this module: the others read facts that do not depend on
 * when they are asked. A token's validity does, so it is taken from the same boundary-injected clock the writes use
 * (RULE B21), and a test can pin the instant.
 */
@Service
public class ReadCurrentUserService implements ReadCurrentUser {

    private final Sessions sessions;
    private final UserAccounts accounts;
    private final Clock clock;

    public ReadCurrentUserService(Sessions sessions, UserAccounts accounts, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Result<SignedInUser> read(String accessToken) {
        Objects.requireNonNull(accessToken, "accessToken");

        Instant now = clock.instant();

        Optional<Sessions.AuthenticatedSession> session = sessions.findByAccessTokenHash(SessionTokens.hash(accessToken))
                .filter(found -> now.isBefore(found.accessExpiresAt()));

        if (session.isEmpty()) {
            return Result.fail(new SignInRefusals.NotSignedIn());
        }

        return accounts.findById(session.get().userId())
                .map(account -> Result.ok(new SignedInUser(account.id(), account.displayName())))
                .orElseGet(() -> Result.fail(new SignInRefusals.NotSignedIn()));
    }
}
