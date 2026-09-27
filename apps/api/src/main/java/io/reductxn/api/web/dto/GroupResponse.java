package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.reductxn.api.port.in.GroupView;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's `Group`, as served.
 *
 * <p>Both currencies are <strong>strings</strong>, because the contract's `Currency` is an ISO code rather than an object:
 * the exponent is reference data the client fetches once (`GET /currencies`, `CurrencyInfoResponse`) rather than something
 * every response repeats. That is why these two fields are codes and not {@code Currency} values - the ledger's type would
 * serialise as {@code {code, minorUnitExponent}}, which is a different schema from the one the contract promises.
 *
 * <p><strong>{@code NON_NULL}, because {@code settlementTimezone} is genuinely absent until a group chooses one.</strong>
 * The schema makes it optional, and a `null` in the payload would say "there is a value and it is nothing" - which a client
 * would then have to special-case. Leaving it out says what is true: this group has not chosen a zone, so no cycle boundary
 * can be derived from it yet (ADR 0014, point 6). The obligation response omits its {@code fxQuote} the same way.
 *
 * <p>Four other fields are worth reading beside the contract. {@code memberCount} is counted from the memberships on every
 * read, so it cannot drift from the rows that are the fact. {@code reportingCurrency} is presentational - what totals
 * spanning currencies are stated in (ADR 0013). {@code maxMembers} is served and nothing consults it yet.
 *
 * <p>There is no {@code nettingPolicy}, and the absence is a decision: the schema allows the field, and the sentence it
 * would carry summarises a policy configuration this build does not have. Composing one from nothing would read as a
 * decision somebody made.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupResponse(
        UUID id,
        String name,
        String currency,
        String reportingCurrency,
        String settlementTimezone,
        int memberCount,
        int maxMembers,
        Instant createdAt) {

    public static GroupResponse of(GroupView group) {
        return new GroupResponse(
                group.id(),
                group.name(),
                group.currency().code(),
                group.reportingCurrency().code(),
                group.settlementTimezone(),
                group.memberCount(),
                group.maxMembers(),
                group.createdAt());
    }
}
