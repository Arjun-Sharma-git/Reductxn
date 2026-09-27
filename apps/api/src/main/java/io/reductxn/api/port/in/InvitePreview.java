package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a code is for: the group it is for, what holding it entitles somebody to, and whether it still works.
 *
 * <p><strong>It carries less than an {@link Invitation} and more than nothing, and the difference is who reads
 * it.</strong> An {@code Invitation} is the stored row - what it grants, its group id, its window, its limit and how
 * often it has been spent - and it exists for the use cases that decide what may be done with it. A preview is for
 * somebody who has not joined: they are told what they would be joining (or looking at) and whether the code still
 * works, and nothing else. No amounts, no member names, no commitments: a group that hands out a code has agreed to say
 * "you are invited" or "you may look at this", not to open its ledger (ADRs 0054, 0055).
 *
 * <p><strong>{@code grants} is what makes this read serve both kinds of code</strong> (ADR 0055), and it is why the
 * operation is no longer called "what an invite code is for": a client shows the acceptance form for a
 * {@link InvitationGrant#MEMBERSHIP} code and the shared view for a {@link InvitationGrant#READ} one, which is a
 * decision it can only make from this field.
 *
 * <p>{@code maxUses} is null for no limit, which is the reading the contract's `Invite` schema already requires - and
 * the row's {@code uses} is deliberately not here: how often a link has been used is the group's business, and the
 * only question a joiner has about it ("is it finished?") is answered by the refusal rather than by a count.
 *
 * @param code      the code, echoed so a screen can show what was typed and what was recognised
 * @param grants    what holding the code entitles somebody to
 * @param expiresAt when the window closes; a use at or after this instant is refused
 * @param maxUses   how many times the code may be spent, or null for no limit
 * @param group     the group the code is for
 */
public record InvitePreview(String code, InvitationGrant grants, Instant expiresAt, Integer maxUses, Group group) {

    public InvitePreview {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(grants, "grants");
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