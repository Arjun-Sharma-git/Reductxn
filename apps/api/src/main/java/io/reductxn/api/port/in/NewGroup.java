package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * A group somebody is creating, at an instant the boundary supplies (RULE B21).
 *
 * <p><strong>The creator is here rather than in the request body, and that is this slice's sharpest decision.</strong>
 * Every other write in this build takes the person from the body - a decision's {@code memberId}, an obligation's
 * {@code debtorId}, an invitation's {@code userId} - because identity did not exist when they were written. A group
 * creation is the first write where that excuse is gone, so it does not accept a uuid at all: the caller is whoever the
 * token says, and a request cannot claim to be somebody else (ADR 0043).
 *
 * <p>The creator's display name travels with them because creating a group <em>makes</em> the creator a member - a group
 * its own creator cannot see is not a group - and a membership row needs a name the group calls them by. Until this
 * build has a profile screen that name is the person's phone number, which is all the deployment knows (ADR 0042).
 *
 * @param name               what the group will be called
 * @param currency           the currency it settles in, by code - resolved through the catalogue, never assumed
 * @param reportingCurrency  the currency for cross-currency headline totals, or null to settle on {@code currency}
 * @param settlementTimezone an IANA zone that anchors the cycle boundary, or null for "not chosen yet"
 * @param maxMembers         how many members the group will hold
 * @param creator            who is creating it, from the token
 * @param idempotencyKey     the request key: a retry answers with the group the first attempt created
 * @param createdAt          when the request arrived
 */
public record NewGroup(
        String name,
        String currency,
        String reportingCurrency,
        String settlementTimezone,
        int maxMembers,
        SignedInUser creator,
        String idempotencyKey,
        Instant createdAt) {

    public NewGroup {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The currency cross-currency totals are stated in: what the request named, or the settlement currency. */
    public String reportingCurrencyOrDefault() {
        return reportingCurrency == null || reportingCurrency.isBlank() ? currency : reportingCurrency;
    }
}
