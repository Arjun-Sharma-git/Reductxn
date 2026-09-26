package io.reductxn.api.web.dto;

/**
 * The contract's `auth/otp/verify` body: a code, the number it was sent to, and the device asking.
 *
 * <p>{@code deviceId} is client-supplied and unverified, like the acceptance's `userId` and for the same reason: there is
 * nothing to check it against. It is recorded on the session so that "where am I signed in" has an answer, and a client
 * should treat its own id as a label rather than as a credential.
 */
public record VerifyOtpBody(String phoneNumber, String code, String deviceId) {
}
