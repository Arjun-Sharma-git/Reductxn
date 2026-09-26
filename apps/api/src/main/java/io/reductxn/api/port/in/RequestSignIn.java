package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.time.Instant;
import java.util.UUID;

/**
 * The use case: send a person a code that proves their phone number.
 *
 * <p><strong>No {@code Result}, and the absence is a decision.</strong> The operation answers {@code 202 Accepted}
 * whether or not the number is one this deployment has seen - a first sign-in is a registration - and telling the caller
 * which numbers have accounts is the enumeration a sign-in endpoint must not offer. The rate limit is the one refusal,
 * and a caller who hits it is told so, because being told to wait is not information about anybody.
 *
 * <p><strong>The code is not in the answer, and that is the point of the delivery port.</strong> A code returned in a
 * response is a code anybody reading the response has, which is the same as no code at all: it goes to the phone, and in
 * this deployment "the phone" is an adapter that logs it (there is no SMS provider - recorded, ADR 0042). What the
 * service knows and the boundary does not is the code itself, which is why nothing here returns it.
 */
public interface RequestSignIn {

    /**
     * Records a challenge for {@code phone}'s number and delivers the code.
     *
     * @return the refusal when this number has asked too often, which is the only way this fails
     */
    Result<Void> request(PhoneNumber phone);
}
