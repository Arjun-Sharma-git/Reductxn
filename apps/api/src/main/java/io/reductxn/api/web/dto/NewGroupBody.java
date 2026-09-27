package io.reductxn.api.web.dto;

/**
 * The contract's `POST /groups` body.
 *
 * <p>No caller field, and the omission is the point: every earlier write in this build took the person from the body - a
 * decision's `memberId`, an obligation's `debtorId`, an invitation's `userId` - because identity did not exist. This
 * operation has a token to read and no callers to break, so it does not accept a uuid for the caller at all (ADR 0043).
 *
 * <p>The currencies are codes rather than objects, which is what the contract's `Currency` schema is: the exponent is
 * reference data the server resolves from its own catalogue, and a client that sent one would be telling the server how to
 * read its own money.
 */
public record NewGroupBody(
        String name,
        String currency,
        String reportingCurrency,
        String settlementTimezone,
        Integer maxMembers) {

    /** The contract's default for {@code maxMembers}, applied when the body does not name one. */
    public static final int DEFAULT_MAX_MEMBERS = 200;
}
