package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * A code being handed back, by somebody who says which number it was for.
 *
 * <p>Both fields are the caller's, and the phone number is what makes the code meaningful: a code is a proof of a
 * number, so verifying one without saying which number is verifying nothing.
 *
 * @param phoneNumber the number the code was sent to
 * @param code        the code, as the caller typed it
 * @param deviceId    which device is signing in - recorded on the session, so a person can see where they are signed in
 * @param verifiedAt  when the code was handed in, from the boundary's clock
 */
public record SignInCode(String phoneNumber, String code, String deviceId, Instant verifiedAt) {

    public SignInCode {
        Objects.requireNonNull(phoneNumber, "phoneNumber");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }
}
