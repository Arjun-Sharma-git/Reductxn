package io.reductxn.api.port.in;

import io.reductxn.netting.api.NetPosition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a share link shows: which group it is for, what that group owes whom, and when the link stops working.
 *
 * <p><strong>It is the balances read, plus a name and a stamp.</strong> The positions are {@code GroupPositions}'
 * answer - the same computation {@link ReadGroupBalances} serves - and each one carries the name the group recorded for
 * that member, because a reader who is not in the group cannot look names up: `GET /groups/{groupId}/members` is the
 * group's own read (ADR 0054). The stamp is {@link #generatedAt()}, because "now" is the server's and a link can be
 * opened at any hour.
 *
 * <p><strong>What is deliberately absent is the point of the record.</strong> No memos, no activity, no list of
 * invitations, no members list, and nothing that could change anything: a group that hands somebody a link to look at
 * what it owes has not agreed to open its ledger, and a code in this build never grants a write. Adding a field here is
 * adding a disclosure, which is why the schema's description states the absence rather than leaving it to be inferred.
 *
 * @param code        the code, echoed so a client can show which link was followed
 * @param expiresAt   when the link stops working
 * @param generatedAt the instant the positions were computed at
 * @param group       the group the link is for
 * @param positions   one entry per member per currency - never summed, because ADR 0012 refuses a cross-currency total
 */
public record SharedView(
        String code,
        Instant expiresAt,
        Instant generatedAt,
        Group group,
        List<SharedPosition> positions) {

    public SharedView {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(group, "group");
        positions = List.copyOf(positions);
    }

    /**
     * The group the link is for, as much of it as somebody who is not a member is entitled to know.
     *
     * <p>The same shape {@link InvitePreview.Group} has, and deliberately not the same type: an invitation preview and a
     * shared view are two answers to two questions, and a shared record would tie them together for nothing - the
     * preview's group has to say nothing about money, and this one has to say nothing about the code's terms beyond its
     * window. {@code name} is null for a group that exists only as rows (ADR 0043); {@code memberCount} is counted from
     * memberships and is always known.
     */
    public record Group(UUID id, String name, int memberCount) {

        public Group {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * One member's position, with what the group calls them.
     *
     * <p>{@code displayName} is null when the group's membership does not name this id, which is a state the balances
     * screen already shows as an identifier: the currency clearing account is not a person, and a member who has left is
     * one the group no longer names. Null rather than a placeholder, for the reason every other absence in this module
     * is null: a guess would be indistinguishable from a fact.
     */
    public record SharedPosition(NetPosition position, String displayName) {

        public SharedPosition {
            Objects.requireNonNull(position, "position");
        }
    }
}