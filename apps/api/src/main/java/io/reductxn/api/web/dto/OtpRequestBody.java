package io.reductxn.api.web.dto;

/**
 * The contract's `auth/otp/request` body: the number to send a code to.
 *
 * <p>No validation here, for the reason {@code MemberRequest} gives: what counts as malformed is decided at the boundary,
 * where the contract is being honoured. What is *not* checked anywhere is whether the number looks like a phone number in
 * some country - that is a formatting opinion, and this deployment stores the number as it was written so that the person
 * who typed it recognises it.
 */
public record OtpRequestBody(String phoneNumber) {
}
