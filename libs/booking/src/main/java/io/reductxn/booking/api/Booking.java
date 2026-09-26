package io.reductxn.booking.api;

import io.reductxn.fx.api.FxConversion;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a cross-currency commitment books: two linked entries and the residual nothing can post.
 *
 * <p>The two entries are in different currencies and each balances on its own (L1, L6), so nothing here
 * balances the pair against anything - they are joined by {@link #linkId()} and by {@link FxRole}, and
 * that linkage is verified across chains rather than within one (L9).
 *
 * <p><strong>The residual is reported rather than booked, and that is a decision with a reason.</strong>
 * ADR 0013 point 3 makes the residual fractional - {@code residualScaled / 10^residualScale}, always
 * inside half a minor unit of the target currency. {@code Posting} requires a strictly positive integer
 * amount (L2), so a residual like {@code -0.096} of a rupee cannot be posted: it is not a small value, it
 * is a value with no representation in the unit the ledger records in. Rounding it up or down to make it
 * postable would either invent or destroy value on every conversion, which is what P1 exists to prevent.
 *
 * <p>So L7's "never dropped" is satisfied by not dropping it: the residual leaves here carrying the quote
 * it came from, in the arithmetic form it actually has. What to do with it - accumulate it, allocate it pro
 * rata, or fund it - is the open question in ADR 0013 and the ledger spec, and inventing an answer inside a
 * booking would hide a policy decision in a constructor.
 *
 * @param base     the debt side: the debtor owes the clearing account, in the debt currency
 * @param quote    the settlement side: the clearing account owes the creditor, in the settlement currency
 * @param residual exactly what rounding discarded, which is why the two sides are not "the same" value
 */
public record Booking(JournalEntry base, JournalEntry quote, FxConversion residual) {

    public Booking {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(residual, "residual");

        if (!base.isFxLinked() || !quote.isFxLinked()) {
            throw new IllegalArgumentException(
                    "both sides of a conversion are linked (L9), but base was "
                            + (base.isFxLinked() ? "linked" : "unlinked") + " and quote was "
                            + (quote.isFxLinked() ? "linked" : "unlinked"));
        }
        if (!base.fxLinkId().equals(quote.fxLinkId())) {
            throw new IllegalArgumentException(
                    "the two sides of a conversion share one link id (L9), but they were "
                            + base.fxLinkId() + " and " + quote.fxLinkId());
        }
        if (base.fxRole() != FxRole.BASE || quote.fxRole() != FxRole.QUOTE) {
            throw new IllegalArgumentException(
                    "a booking is one base side and one quote side, but the roles were "
                            + base.fxRole() + " and " + quote.fxRole());
        }
        if (!base.groupId().equals(quote.groupId())) {
            throw new IllegalArgumentException(
                    "a link pairs entries in one group (L9), but they were in " + base.groupId()
                            + " and " + quote.groupId());
        }
        // A conversion into the same currency has no rate and no residual; it is a single-currency
        // commitment that took the long way round, and pairing it would create a link that L9 can verify
        // and no auditor can explain.
        if (base.currency().equals(quote.currency())) {
            throw new IllegalArgumentException(
                    "a linked pair is in two currencies, but both sides were in "
                            + base.currency().code());
        }
    }

    /** Both sides, in the order they should be appended: base, then quote. */
    public List<JournalEntry> entries() {
        return List.of(base, quote);
    }

    /** The id that joins the two sides, which is how a verifier finds the partner of either. */
    public UUID linkId() {
        return base.fxLinkId();
    }

    @Override
    public String toString() {
        return "booking link " + linkId() + ": " + base + " + " + quote
                + (residual.residualIsZero() ? " (exact)" : " (residual " + residual.residualAsDecimalString() + ")");
    }
}
