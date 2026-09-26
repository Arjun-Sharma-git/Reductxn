package io.reductxn.api.application.identity;

import io.reductxn.api.port.in.IssuedTokens;
import io.reductxn.api.port.in.SignInCode;
import io.reductxn.api.port.in.SignInRefusals;
import io.reductxn.api.port.in.VerifySignIn;
import io.reductxn.api.port.out.OtpChallenges;
import io.reductxn.api.port.out.Sessions;
import io.reductxn.api.port.out.UserAccounts;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: a code came back, so sign the person in.
 *
 * <p><strong>Three writes, so this one has a transaction.</strong> The challenge is spent, the account is found or
 * created, and the session is recorded - and the first of those is a promise: a code is spent *because* somebody signed
 * in. A failure between the writes would burn a code without signing anybody in, which is the same shape of mistake the
 * invitation's acceptance avoids and the same reason the annotation is there (RULE B15).
 *
 * <p><strong>Every failure answers one code, and the reason is a security one rather than a lazy one.</strong> An unknown
 * number, an expired code, a spent code and a wrong code are all {@code api.otp_not_accepted}: telling a caller which of
 * those it was tells whoever is guessing whether the number has a live challenge, and the whole point of a six-digit code
 * is that guessing it is not a strategy. A wrong code does register an attempt, so a code being brute-forced dies after
 * {@link #MAX_ATTEMPTS} - which is the protection that makes the honest "not one this deployment will accept" affordable.
 *
 * <p><strong>Signing in is registering, and the name is the number.</strong> A number nobody has seen becomes an account
 * here, because in an OTP build the code *is* the registration. What this deployment calls them is the number itself -
 * a name nobody chose, and the group's own name for them ({@code group_members.display_name}) is the one that means
 * something to a person. Inventing "User 4711" would be a name that looks chosen and is not.
 */
@Service
public class VerifySignInService implements VerifySignIn {

    /** How many wrong codes a challenge survives. A phone number is not a password. */
    static final int MAX_ATTEMPTS = 5;

    /** How long an access token lasts. Short, because a refresh is cheap and a stolen token is not. */
    static final Duration ACCESS_LIFETIME = Duration.ofMinutes(30);

    /**
     * How long a session lasts. Thirty days, which is what "signed in on my phone" means - and it is the refresh token's
     * window rather than the access token's, so renewing the access half does not extend the session.
     */
    static final Duration SESSION_LIFETIME = Duration.ofDays(30);

    private final OtpChallenges challenges;
    private final UserAccounts accounts;
    private final Sessions sessions;

    public VerifySignInService(OtpChallenges challenges, UserAccounts accounts, Sessions sessions) {
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    @Transactional
    public Result<IssuedTokens> verify(SignInCode code) {
        Objects.requireNonNull(code, "code");

        Optional<OtpChallenges.StoredChallenge> found = challenges.newestUnspent(code.phoneNumber());

        if (found.isEmpty()) {
            return Result.fail(new SignInRefusals.OtpNotAccepted(code.phoneNumber()));
        }

        OtpChallenges.StoredChallenge challenge = found.get();

        // Half-open, like every window in this codebase: at the expiry instant the code is already too late.
        if (!code.verifiedAt().isBefore(challenge.expiresAt()) || challenge.attempts() >= MAX_ATTEMPTS) {
            return Result.fail(new SignInRefusals.OtpNotAccepted(code.phoneNumber()));
        }

        if (!OneTimeCodeHash.matches(code.code(), challenge.salt(), challenge.codeHash())) {
            challenges.registerAttempt(challenge.id());

            return Result.fail(new SignInRefusals.OtpNotAccepted(code.phoneNumber()));
        }

        challenges.spend(challenge.id(), code.verifiedAt());

        UserAccounts.Account account = accounts.findOrCreate(code.phoneNumber(), code.phoneNumber(), code.verifiedAt());

        return Result.ok(issue(account.id(), code.deviceId(), code.verifiedAt()));
    }

    /** Draws a token pair, records the session, and counts the access token's seconds for the contract's `expiresIn`. */
    private IssuedTokens issue(UUID userId, String deviceId, Instant at) {
        String accessToken = SessionTokens.issue();
        String refreshToken = SessionTokens.issue();

        Instant accessExpiresAt = at.plus(ACCESS_LIFETIME);
        Instant refreshExpiresAt = at.plus(SESSION_LIFETIME);

        sessions.create(new Sessions.NewSession(
                SessionTokens.hash(accessToken),
                SessionTokens.hash(refreshToken),
                userId,
                deviceId,
                at,
                accessExpiresAt,
                refreshExpiresAt));

        return new IssuedTokens(accessToken, refreshToken, ACCESS_LIFETIME.toSeconds());
    }
}
