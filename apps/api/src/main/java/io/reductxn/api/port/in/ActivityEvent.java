package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing that happened in a group.
 *
 * <p><strong>Derived from the row that recorded it, not from a log.</strong> There is no activity table and there is not
 * going to be one: a membership, a settlement decision and an invitation already state what happened, and an event table
 * would be a second copy of those facts whose disagreement with them nobody could settle. What this type is, then, is a
 * reading of three tables in one vocabulary (ADR 0044).
 *
 * <p><strong>Optional fields rather than a `detail` string.</strong> A server that composed "Amit joined on Tuesday" would
 * be rendering one language at a client that may be reading another, and would be deciding the sentence a client shows
 * (RULE B20, ADR 0014). So each kind carries the fields it has and the client renders them; which fields belong to which
 * kind is stated in the contract rather than left to be discovered.
 *
 * <p>{@code at} always comes from the row's own column - {@code joined_at}, {@code decided_at}, {@code created_at} - and
 * never from the read's clock, because a feed ordered by when somebody looked at it is not a feed.
 *
 * @param kind        what happened
 * @param at          when it happened, from the row
 * @param memberId    who did it, when the row names a person; null for an invitation, which names nobody
 * @param displayName what the group calls them, when the row records it; null for a decision, which does not
 * @param role        the membership's role, for {@link Kind#MEMBER_JOINED}; null otherwise
 * @param decision    the answer given to a run, for {@link Kind#DECISION_RECORDED}; null otherwise
 * @param uses        how many times the code has been spent, for {@link Kind#INVITATION_CREATED}; null otherwise
 * @param maxUses     how many times it may be spent; null when unbounded or for another kind
 */
public record ActivityEvent(
        Kind kind,
        Instant at,
        UUID memberId,
        String displayName,
        String role,
        String decision,
        Integer uses,
        Integer maxUses) {

    /**
     * The closed set of things a group's history contains.
     *
     * <p>Three, and the contract's schema is an enum of exactly these: a member joining, a member answering a settlement
     * run, and an invitation being created. Obligations, cycles and runs are deliberately absent - each has its own read and
     * its own screen, and a feed that repeated them would be a second view of one set of rows that could disagree with the
     * first about ordering, filters and status.
     */
    public enum Kind {
        MEMBER_JOINED("member_joined"),
        DECISION_RECORDED("decision_recorded"),
        INVITATION_CREATED("invitation_created");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** The name the contract promises. Written out in a switch rather than derived from the constant's name. */
        public String wire() {
            return wire;
        }
    }

    public ActivityEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(at, "at");
    }

    /**
     * The feed's order, declared once: newest first, and total when two rows share an instant.
     *
     * <p>It lives on the value rather than in the use case or the adapter because both need it and neither owns it: the
     * service's job is to narrow and clamp, and the store's is to merge three sources - and the adapter may not depend on the
     * application layer at all (RULE B14, which caught exactly that in this slice's first version). A tie-break on kind and
     * then on the member makes the order total, so a page is a function of the rows rather than of the database's mood.
     */
    public static final Comparator<ActivityEvent> NEWEST_FIRST = Comparator.comparing(ActivityEvent::at).reversed()
            .thenComparing(event -> event.kind().wire())
            .thenComparing(event -> event.memberId() == null ? "" : event.memberId().toString());

    public static ActivityEvent memberJoined(UUID memberId, String displayName, String role, Instant at) {
        return new ActivityEvent(Kind.MEMBER_JOINED, at, memberId, displayName, role, null, null, null);
    }

    public static ActivityEvent decisionRecorded(UUID memberId, String decision, Instant at) {
        return new ActivityEvent(Kind.DECISION_RECORDED, at, memberId, null, null, decision, null, null);
    }

    public static ActivityEvent invitationCreated(Integer uses, Integer maxUses, Instant at) {
        return new ActivityEvent(Kind.INVITATION_CREATED, at, null, null, null, null, uses, maxUses);
    }
}
