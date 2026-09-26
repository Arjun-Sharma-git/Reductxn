package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.GroupMember;

import java.util.UUID;

/**
 * The contract's `Member`: what the group calls one of its members, and the role it recorded for them.
 *
 * <p>Two fields and no policy. `creditLimit` was in the contract and is gone (ADR 0035): nothing sets one, nothing
 * consults one, and a field that is always absent is a promise a client waits for - it comes back with the policy
 * that enforces it.
 *
 * <p>`role` is passed through as the string the schema names rather than as an enum, which mirrors the contract:
 * the vocabulary is fixed by the table's `CHECK` constraint, and a client that enumerated the roles it had heard of
 * would break on a role a later build learned - the same reasoning the client applies to a run's status.
 */
public record MemberResponse(UUID userId, String displayName, String role) {

    public static MemberResponse of(GroupMember member) {
        return new MemberResponse(member.userId(), member.displayName(), member.role());
    }
}
