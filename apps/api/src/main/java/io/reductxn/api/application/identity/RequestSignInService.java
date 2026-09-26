package io.reductxn.api.application.identity;

import io.reductxn.api.port.in.PhoneNumber;
import io.reductxn.api.port.in.RequestSignIn;
import io.reductxn.api.port.in.SignInRefusals;
import io.reductxn.api.port.out.OneTimeCodes;
import io.reductxn.api.port.out.OtpChallenges;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: send a code that proves a phone number.
 *
 * <p><strong>Three constants, and they are the whole of this class's policy.</strong> A code lives five minutes, a number
 * may ask for three of them in ten, and both numbers are here rather than in properties for the reason
 * {@code CreateInviteService} gives: a window a deployment can change is a window whose behaviour nobody can reproduce
 * from the code. The contract's `429` is the third constant's refusal, and it is the one the contract already declared -
 * an endpoint that sends a message on every request is a way to spend somebody else's money.
 *
 * <p><strong>The rate limit is checked before the write, and that means a race can send one code too many.</strong> Two
 * requests for one number in the same millisecond can both count two challenges and both insert. The honest statement is
 * that this is a soft limit: it is not what protects a code (the hash, the expiry and the attempt count are), and closing
 * the race would need either a lock on a number nobody is holding or a counter table with its own window. Recorded rather
 * than papered over (ADR 0042).
 *
 * <p><strong>No {@code @Transactional}.</strong> The count is a read and the record is one insert; a transaction would
 * span them without preventing the race above, which is exactly the misreading RULE B15 keeps the annotation away from.
 */
@Service
public class RequestSignInService implements RequestSignIn {

    /** How long a code is good for. */
    static final Duration CODE_LIFETIME = Duration.ofMinutes(5);

    /** How many codes one number may ask for ... */
    static final int CODES_PER_WINDOW = 3;

    /** ... in this long. */
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final OtpChallenges challenges;
    private final OneTimeCodes delivery;
    private final SecureRandom random = new SecureRandom();

    public RequestSignInService(OtpChallenges challenges, OneTimeCodes delivery) {
        this.challenges = Objects.requireNonNull(challenges, "challenges");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    @Override
    public Result<Void> request(PhoneNumber phone) {
        Objects.requireNonNull(phone, "phone");

        String number = phone.phoneNumber();
        Instant at = phone.requestedAt();

        if (challenges.countRequestedSince(number, at.minus(WINDOW)) >= CODES_PER_WINDOW) {
            return Result.fail(new SignInRefusals.TooManyCodes(number, CODES_PER_WINDOW));
        }

        String code = OneTimeCodeHash.code(random);
        byte[] salt = OneTimeCodeHash.salt(random);

        challenges.record(new OtpChallenges.NewChallenge(
                UUID.randomUUID(),
                number,
                OneTimeCodeHash.hash(code, salt),
                salt,
                at,
                at.plus(CODE_LIFETIME)));

        // The code leaves the application here and nowhere else. It is not returned, not logged by this class, and not
        // held anywhere after this line: the row holds a hash, and the delivery adapter is the only thing that ever sees
        // the plaintext.
        delivery.deliver(number, code);

        return Result.ok(null);
    }
}
