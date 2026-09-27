package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;


import io.reductxn.api.port.in.InvitePreview;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's `InvitePreview` schema: what a code is for, and whether it still works.
 *
 * <p><strong>{@code NON_NULL} on both levels, for two different absences.</strong> {@code maxUses} is absent when the
 * code has no limit, which is the same reading {@link InviteResponse} requires - and `group.name` is absent when the
 * group exists only as rows, because {@code group_members} and {@code invitations} write a bare uuid and a group with
 * no `groups` row of its own is a state this build contains (ADR 0043). The contract makes `name` optional and
 * `id`/`memberCount` required, which is exactly the split: a client can always show something, and the name is what it
 * shows instead of the id when there is one.
 *
 * <p>The two fields are not the same kind of absence, and the schema says so: {@code maxUses} absent means "no limit"
 * (an absent value with a meaning), while {@code name} absent means "this group has no name to show" (a missing
 * fact). Both are the contract's own words rather than this class's judgement - the reason there is no
 * {@code Optional} anywhere in this record, which would put the distinction in a Java type the wire cannot see.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvitePreviewResponse(
        String code,
        String grants,
        Instant expiresAt,
        Integer maxUses,
        Group group) {

    /** What a code joins somebody to: the group's id always, its name when it has one, and how many members. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(UUID id, String name, int memberCount) {
    }

    public static InvitePreviewResponse of(InvitePreview preview) {
        return new InvitePreviewResponse(
                preview.code(),
                preview.grants().wireName(),
                preview.expiresAt(),
                preview.maxUses(),
                new Group(preview.group().id(), preview.group().name(), preview.group().memberCount()));
    }
}