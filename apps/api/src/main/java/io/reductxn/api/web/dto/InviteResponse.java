package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.CreatedInvite;


import java.time.Instant;

/**
 * The contract's `Invite` schema: a code, what holding it entitles somebody to, when it stops working, and how many
 * times it may be used.
 *
 * <p>{@code maxUses} is absent when there is no limit, which is the schema's own word for it - the property is optional,
 * and both creation operations' descriptions say that a code has no limit. A `null` in the body would say the same thing
 * less clearly, and a zero would say something else entirely (the table refuses it for that reason). This is the
 * opposite of {@code TaxRuleResponse.effectiveTo}, which is present and null because *that* schema declares it nullable
 * - the contract is what decides, per field.
 *
 * <p><strong>{@code grants} is what tells the two creation operations' answers apart</strong> (ADR 0055): the shape is
 * one shape, because a code is one instrument, and this field is the only thing that differs between "whoever holds this
 * can join" and "whoever holds this can look". A client showing a code says which, and a test asserting what a creation
 * made is asserting the thing that distinguishes them.
 *
 * <p>The code is capitalised as generated. Uppercasing the alphabet and refusing lowercase input would be a
 * normalisation this API invented - and one that would make a code typed in lowercase fail for a reason nobody could
 * see - so the boundary compares the code as given, and the client that shows a code shows it as it was made.
 */
public record InviteResponse(String code, String grants, Instant expiresAt, Integer maxUses) {

    /** What a creation answers with, whether it was an invitation or a share link. */
    public static InviteResponse of(CreatedInvite created) {
        return new InviteResponse(created.code(), created.grants().wireName(), created.expiresAt(), created.maxUses());
    }
}
