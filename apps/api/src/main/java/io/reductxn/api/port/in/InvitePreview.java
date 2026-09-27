package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a code is for: the group it joins somebody to, and the code's own terms.
 *
 * <p><strong>It carries less than an {@link Invitation} and more than nothing, and the difference is who reads
 * it.</strong> An {@code Invitation} is the stored row - its group id, its window, its limit and how often it has been
 * spent - and it exists for the use case that decides whether a spend is allowed. A preview is for somebody who has not
 * joined: they are told what they would be joining and whether the code still works, and nothing else. No amounts, no
 * member names, no commitments: a group that hands out a code has agreed to say "you are invited", not to open its
 * ledger (ADR 0054).
 *
 * <p>{@code maxUses} is null for no limit, which is the reading the contract's `Invite` schema already requires - and
 * the row's {@code uses} is deliberately not here: how often a link has been used is the group's business, and the
 * only question a joiner has about it ("is it finished?") is answered by the refusal rather than by a count.
 *
 * @param code      the code, echoed so a screen can show what was typed and what was recognised
 * @param expiresAt when the window closes; an acceptance at or after this instant is refused
 * @param maxUses   how many times the code may be spent, or null for no limit
 * @param group     the group the code joins somebody to
 */
public record InvitePreview(String code, Instant expiresAt, Integer maxUses, Group group) {

    public InvitePreview {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(group, "group");
    }

    /**
     * The group, as much of it as somebody who has not joined is entitled to know.
     *
     * <p><strong>{@code name} may be null, and that is a state rather than a missing value.</strong>
     * {@code group_members} and {@code invitations} write a bare uuid, so a group with members and invitations and no
     * {@code groups} row of its own really is in this build (ADR 0043 added the table rather than migrating the data).
     * Its codes are valid and its previews work; there is simply no name to show, and the client falls back to the id -
     * the same fallback its group layout already makes for the same reason. {@code memberCount} is always known,
     * because it is counted from memberships.
     */
    public record Group(UUID id, String name, int memberCount) {

        public Group {
            Objects.requireNonNull(id, "id");
        }
    }
}