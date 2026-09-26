package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * The {@code NettingPreview} schema from {@code contracts/openapi/reductxn-api.yaml}.
 *
 * <p>Hand-written rather than generated, because the codegen gate (RULE C2/C3) is not built yet. When it
 * is, these types are replaced by generated ones and this file - not the mapper - is what disappears:
 * the generated DTOs are the compile-time check that the contract and the code agree.
 *
 * <p><strong>Nulls mean absent.</strong> Half of these fields are optional and mean something by their
 * absence - {@code currency} is present only for a single-currency preview, {@code reportingTotals} only
 * when a reporting rate was applied - so writing them as {@code null} would turn "not applicable" into
 * "zero" for any client that reads the key without checking it.
 *
 * <p>{@code MoneyDto} rather than {@code Money} because {@code io.reductxn.ledger.api.Money} is the
 * ledger's exact-money type and two types with one name in one mapper is how a wire format and a domain
 * concept start being confused for each other.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NettingPreviewResponse(
        String currency,
        MoneyDto grossTax,
        MoneyDto netTax,
        MoneyDto taxSaved,
        Boolean provablyOptimal,
        List<Partition> partitions,
        BestCase bestCase) {

    /**
     * One currency's summary. Authoritative for a multicurrency group: ADR 0012 keeps the partitions
     * separate rather than collapsing them into a base currency, and {@code reportingTotals} exists only
     * for display.
     */
    public record Partition(
            String currency,
            int grossTransferCount,
            int netTransferCount,
            MoneyDto grossTax,
            MoneyDto netTax,
            MoneyDto taxSaved,
            String algorithm) {
    }

    /**
     * The plan itself, rather than the summary: the positions and the transfers that settle the group.
     *
     * <p>For a multicurrency group this is the union of the per-currency plans, and every entry carries
     * its own currency, so no base currency has to be chosen to read it. Collapsing it into one currency
     * is what {@code reportingTotals} is for, and that needs a reporting rate.
     */
    public record BestCase(List<NetPositionResponse> positions, List<Transfer> transfers) {
    }

    /**
     * One transfer that must happen.
     *
     * <p>{@code status} is always {@code pending} in a preview - nothing has been submitted, which is what
     * makes it a preview. {@code toAccountType} is what makes the FX cost of a plan visible instead of
     * hidden inside a member's transfer (ADR 0012).
     */
    public record Transfer(
            UUID fromUserId,
            UUID toUserId,
            String toAccountType,
            MoneyDto amount,
            MoneyDto tax,
            String status) {
    }
}
