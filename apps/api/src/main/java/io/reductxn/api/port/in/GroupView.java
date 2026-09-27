package io.reductxn.api.port.in;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A group as the API knows it: what it is called, what it settles in, and how many members it holds.
 *
 * <p><strong>This is the contract's {@code Group}, as a value.</strong> {@code memberCount} is counted from the
 * memberships rather than stored: a count column would be a second copy of a fact the rows already state, and the first
 * membership written outside the operation that maintained it would make the two disagree.
 *
 * <p>Three fields are worth naming because of what they are not. {@code settlementTimezone} is <strong>null</strong>
 * until the group chooses one - the contract makes it optional, and defaulting to UTC would decide which cycle an
 * obligation belongs to for a household somewhere else (ADR 0014, point 6). {@code maxMembers} is <em>recorded and not
 * enforced</em>, exactly as a member's {@code role} is. {@code reportingCurrency} is presentational - it is what headline
 * totals spanning currencies are stated in, and it restates nothing about anybody's obligation (ADR 0013).
 *
 * @param currency          what the group settles in
 * @param reportingCurrency what its cross-currency totals are stated in - the settlement currency unless it was set
 * @param settlementTimezone the zone that anchors the cycle boundary, or null when nobody has chosen one
 * @param memberCount       how many memberships the group has
 * @param maxMembers        how many it will hold, which nothing consults yet
 */
public record GroupView(
        UUID id,
        String name,
        Currency currency,
        Currency reportingCurrency,
        String settlementTimezone,
        int memberCount,
        int maxMembers,
        Instant createdAt) {

    public GroupView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(reportingCurrency, "reportingCurrency");
        Objects.requireNonNull(createdAt, "createdAt");

        if (memberCount < 0) {
            throw new IllegalArgumentException(
                    "a group cannot have " + memberCount + " members. The count is read from the memberships, so this "
                            + "means something counted rows that are not there");
        }
    }

    /** The schema's default when a request does not name a number: {@code POST /groups} says 200, and so does this. */
    public static final int DEFAULT_MAX_MEMBERS = 200;
}
