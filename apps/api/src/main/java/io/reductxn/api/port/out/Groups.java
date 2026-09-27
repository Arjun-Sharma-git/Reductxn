package io.reductxn.api.port.out;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The groups, and the memberships that decide who can see them. Owned by {@code apps/api} (MASTER-PLAN section 5.5).
 *
 * <p><strong>Membership is the store's condition, not the service's check.</strong> {@link #forMember} and
 * {@link #ofMember} both join the memberships, so there is no window between "is this person allowed" and "read the row"
 * in which an answer could be given about a group somebody is not in. A service that asked {@code findById} and then
 * checked membership would have to trust itself to remember to check, in every future caller.
 *
 * <p><strong>{@link #create} takes the idempotency key and returns whether it wrote.</strong> A retry has to answer with
 * the group the first attempt created, which means the store is the only place that can tell the two apart - it is the
 * unique index on {@code (created_by, idempotency_key)} that settles it, and a check-then-insert in a service would lose
 * the race and report it as a failure (the shape {@code JooqGroupMembers.add} documents).
 *
 * <p>The two records are the row and the request. {@code StoredGroup} carries {@code memberCount} because the read that
 * finds a group can count its members in the same statement; making the caller count them separately would be one query
 * per group on a list screen.
 */
public interface Groups {

    /** Writes the group, or finds the one this creator's key already made. */
    Created create(NewGroupRow row);

    /** The groups this person is a member of, most recently joined first. */
    List<StoredGroup> ofMember(UUID userId);

    /** One group, but only if this person is a member of it. */
    Optional<StoredGroup> forMember(UUID groupId, UUID userId);

    /**
     * Whether this person is a member of this group id, and nothing else.
     *
     * <p><strong>It exists because {@link #forMember} requires a group <em>row</em>, and this build is full of groups that
     * have none.</strong> A read that only needs to know whether the caller belongs - the activity feed, which narrows to
     * the memberships and reads no group column at all, and since ADR 0054 the nine reads that take a group id and serve
     * its books - would otherwise have answered `404` for a group whose commitments and members are perfectly readable,
     * which is a refusal about a row rather than about a person. Ten reads ask it now, and the sentence behind all ten is
     * the same: being in a group is a fact about a membership row, not about the group having a name.
     *
     * <p><strong>This paragraph used to argue the other way, and it is worth keeping because of how it was wrong.</strong>
     * It said the members list, the obligations list and the cycles list all served a bare uuid, so a feed that refused to
     * "would be the inconsistency a member notices first". That was a description of the build rather than a decision about
     * it - nine reads were open to anybody holding an id and the one read that asked was the outlier - and ADR 0054
     * closed the nine, which turned this method from the exception into the shared condition. A javadoc is exactly where a
     * wrong argument can sit for a long time, because nothing fails when it is wrong: this one described a hole as a
     * design until the slice that closed the hole read it.
     */
    boolean isMember(UUID groupId, UUID userId);

    /** A group to write. */
    record NewGroupRow(UUID id, String name, Currency currency, Currency reportingCurrency, String settlementTimezone,
            int maxMembers, UUID createdBy, String idempotencyKey, Instant createdAt) {
    }

    /**
     * A group as stored, with the count read beside it.
     *
     * @param settlementTimezone null when nobody has chosen one, which is a real state rather than a missing value
     * @param memberCount        how many memberships the group has
     */
    record StoredGroup(UUID id, String name, Currency currency, Currency reportingCurrency, String settlementTimezone,
            int memberCount, int maxMembers, Instant createdAt) {
    }

    /** The row, and whether this call is what inserted it. */
    record Created(StoredGroup group, boolean created) {
    }
}
