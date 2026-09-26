package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

/**
 * Identity's expected failures, as values.
 *
 * <p>One type per code with {@code public static final String CODE}, for the reason {@link ObligationRefusals}
 * documents: {@code DomainErrorCodeTest} enumerates every concrete {@code DomainError} so the error-code parity gate
 * (I3) can list what the server may answer with, and the client's catalogue has a sentence for each.
 *
 * <p><strong>Three codes, and the count is a security decision.</strong> A wrong code, an expired code, a code with no
 * attempts left and a number that never asked for one are all {@link OtpNotAccepted}: one code, because telling the
 * caller <em>which</em> of those it was tells an attacker whether the number has a live challenge. The same reasoning
 * gives {@link NotSignedIn} both the unknown bearer token and the rotated-away refresh token - "this credential is not
 * a session" is one condition, and distinguishing them would only help somebody guessing.
 *
 * <p>{@link TooManyCodes} is the third, and it is the one the contract already declared: {@code 429} on the request
 * operation, since an OTP endpoint that will send a message on every request is a way to spend somebody else's money.
 */
public final class SignInRefusals {

    private SignInRefusals() {
    }

    /** This number has asked for more codes than the deployment will send in a window. */
    public record TooManyCodes(String phoneNumber, int limit) implements DomainError {

        public static final String CODE = "api.too_many_otp_requests";

        public TooManyCodes {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return phoneNumber + " has asked for " + limit + " codes in the last few minutes, which is as many as this "
                    + "deployment sends. Waiting is the answer: each code is valid for several minutes";
        }
    }

    /** The code is wrong, expired, already spent, or was never requested - one answer for four causes. See the class. */
    public record OtpNotAccepted(String phoneNumber) implements DomainError {

        public static final String CODE = "api.otp_not_accepted";

        public OtpNotAccepted {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the code for " + phoneNumber + " is not one this deployment will accept. A code is valid for a few "
                    + "minutes and for a handful of attempts, and asking for another replaces the one before it";
        }
    }

    /** There is no session behind this credential. Unknown, expired and rotated-away are the same answer. */
    public record NotSignedIn() implements DomainError {

        public static final String CODE = "api.not_signed_in";

        public NotSignedIn {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "this request carries no session this deployment knows. A token is valid for a while and is replaced "
                    + "when a session is renewed, so a client that kept an old one has to sign in again";
        }
    }
}
