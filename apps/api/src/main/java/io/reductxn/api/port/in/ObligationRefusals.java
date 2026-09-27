package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.UUID;

/**
 * The obligation domain's expected failures, as values.
 *
 * <p>One type per code, each declaring {@code public static final String CODE}. That is not ceremony:
 * {@code DomainErrorCodeTest} requires it of every concrete {@code DomainError} so the error-code parity gate
 * (I3) can enumerate every code the server can emit. A single record holding four codes - the first shape this
 * took - cannot be enumerated, and it was the guard that said so.
 *
 * <p>The records implement {@link DomainError} rather than extending an exception, because the kernel's rule is
 * that expected failures are values and only programming faults throw. These are also not the domain model's
 * guards: {@code SingleCurrencyObligation} already refuses a debtor who is their own creditor, and it throws
 * when it does, correctly, because from where it sits that means the code that built the obligation is wrong.
 * Here the same condition arrives as a client's request, where it is an expected failure with a code and a 422
 * - so the use case checks first and the model's guard stays the backstop it is meant to be.
 *
 * <p><strong>Why the codes are namespaced {@code api}.</strong> Two written rules meet here and disagree. The
 * contract says a code's first segment names "the owning domain module", and its examples are
 * {@code ledger.currency_mismatch}, {@code fx.rate_stale}, {@code settlement.fx_unfunded} - which reads like
 * {@code obligation.*} for an obligation. {@code DomainErrorCodeTest} instead derives the namespace from the
 * package's module segment, and every one of these classes lives in {@code io.reductxn.api..}, whose segment is
 * {@code api}. The library modules are their own namespaces, so the two rules agree for them and diverge for
 * the one deployable that owns several domains - identity, groups and obligations all being {@code apps/api}'s.
 * The mechanical rule won, because it is the one that runs: {@code api.obligation_*} is attributable, unique and
 * gate-able. If apps/api's domains ever become modules of their own, or the guard learns that a deployable owns
 * several namespaces, these constants are the one place that changes.
 */
public final class ObligationRefusals {

    private ObligationRefusals() {
    }

    /** A member cannot owe themselves: the two ids are one id. */
    public record SameParty(UUID member) implements DomainError {

        public static final String CODE = "api.obligation_same_party";

        public SameParty {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "debtor and creditor are both member " + member + ", so there is nobody on the other side "
                    + "of the commitment. An obligation to oneself would post a member against themselves and "
                    + "balance perfectly while moving nothing";
        }
    }

    /** An obligation is a magnitude, and a commitment of nothing is not an obligation. */
    public record AmountNotPositive(long amountMinor, String currency) implements DomainError {

        public static final String CODE = "api.obligation_amount_not_positive";

        public AmountNotPositive {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "an obligation must be for a positive amount, but this one is for " + amountMinor + " "
                    + currency + ". Direction is expressed by the debtor and creditor, never by the sign of "
                    + "the amount";
        }
    }

    /** The request names a currency the catalogue does not have. */
    public record UnknownCurrency(String currency) implements DomainError {

        public static final String CODE = "api.obligation_unknown_currency";

        public UnknownCurrency {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "currency '" + currency + "' is not in the currency catalogue, so its minor-unit exponent "
                    + "is unknown and no exact amount can be built from it";
        }
    }

    /**
     * No obligation with that id, or none the caller is a member of.
     *
     * <p>One code for both, exactly as the group reads and the activity feed do it (ADR 0043, 0044): telling "no such
     * obligation" apart from "not your group's obligation" would let anybody holding a guess learn which ids are real. It is
     * also why the membership is checked before the status below - a non-member learns nothing about a commitment, not even
     * whether it is still open.
     */
    public record NoSuchObligation(UUID obligationId) implements DomainError {

        public static final String CODE = "api.no_such_obligation";

        public NoSuchObligation {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "there is no obligation " + obligationId + " that you are a member of the group of. "
                    + "An obligation you cannot see is answered as one that does not exist, so that a guess cannot "
                    + "tell you which ids are real";
        }
    }

    /**
     * The obligation is not open, so there is nothing to void.
     *
     * <p>A settlement run holds it (`netted`) or has taken it (`settled`). `409` rather than `422`, because the request is
     * well-formed and would have succeeded earlier: the world is what refuses it, and the remedy is a cycle rather than a
     * retry (the same distinction {@code DecisionRefusals.AlreadyAnswered} makes). An already-*voided* obligation is not this
     * refusal - it is a `200` with the row, which is what makes the operation idempotent by state rather than by key.
     */
    public record NotOpen(UUID obligationId, String status) implements DomainError {

        public static final String CODE = "api.obligation_not_open";

        public NotOpen {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "obligation " + obligationId + " is " + status + ", so there is nothing to void. A run holds it or "
                    + "has already taken it, and a commitment that left a plan's books would make the plan wrong about "
                    + "money that may already have moved";
        }
    }

    /**
     * A field the contract requires is missing or blank.
     *
     * <p>The field is named in the message rather than in a code of its own: a code per field would multiply
     * contract surface by the shape of one request, and the contract's {@code Problem} schema has a
     * {@code params} field for exactly this - which {@code ProblemTranslator} does not write yet, so the detail
     * carries it until a client needs to interpolate it.
     */
    public record RequestIncomplete(String field) implements DomainError {

        public static final String CODE = "api.obligation_request_incomplete";

        public RequestIncomplete {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the request is missing '" + field + "', which the contract requires. Refused rather than "
                    + "defaulted: a commitment with no stated amount, or with no memo explaining it, is not "
                    + "the commitment the client meant to record";
        }
    }

    /** A memo longer than the contract's 280 characters. Truncating one silently is the alternative. */
    public record MemoTooLong(int length) implements DomainError {

        public static final String CODE = "api.obligation_memo_too_long";

        public MemoTooLong {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the memo is " + length + " characters long and the contract allows 280. Refused rather "
                    + "than truncated, because shortening what a person wrote changes what it says";
        }
    }
}
