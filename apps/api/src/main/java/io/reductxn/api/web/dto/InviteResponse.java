package io.reductxn.api.web.dto;

import java.time.Instant;

/**
 * The contract's `Invite` schema: a code, when it stops working, and how many times it may be used.
 *
 * <p>{@code maxUses} is absent when there is no limit, which is the schema's own word for it - the property is optional,
 * and the operation's description says that a created code has no limit. A `null` in the body would say the same thing
 * less clearly, and a zero would say something else entirely (the table refuses it for that reason). This is the
 * opposite of {@code TaxRuleResponse.effectiveTo}, which is present and null because *that* schema declares it nullable
 * - the contract is what decides, per field.
 *
 * <p>The code is capitalised as generated. Uppercasing the alphabet and refusing lowercase input would be a
 * normalisation this API invented - and one that would make a code typed in lowercase fail for a reason nobody could
 * see - so the boundary compares the code as given, and the client that shows a code shows it as it was made.
 */
public record InviteResponse(String code, Instant expiresAt, Integer maxUses) {
}
