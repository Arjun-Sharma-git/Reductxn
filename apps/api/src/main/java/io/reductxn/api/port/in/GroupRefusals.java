package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.UUID;

/**
 * Groups' expected failures, as values.
 *
 * <p>Two of the conditions this use case can meet already have codes elsewhere in the module, and they are reused rather
 * than renamed: a blank name is {@code RunRefusals.RequestIncomplete} - the code four other boundaries answer a missing
 * field with - and a currency the catalogue does not hold is {@code ObligationRefusals.UnknownCurrency}, which ADR 0037
 * already reused for the rates read on the ground that one condition has one code. A third code for "not in the
 * catalogue" would let a client handle the same fact in two places.
 *
 * <p>The two here are new because the conditions are: a group this caller cannot see, a name the contract's
 * {@code maxLength} refuses, and a timezone nobody could interpret.
 */
public final class GroupRefusals {

    private GroupRefusals() {
    }

    /**
     * No group with this id that this caller belongs to.
     *
     * <p><strong>One answer for "does not exist" and "is not yours", and the sameness is deliberate.</strong> Telling the
     * two apart would tell anybody holding a guess whether the id is real, which is the enumeration a group list must not
     * offer - the same reasoning that makes an unknown phone number and a wrong code one answer on the sign-in path
     * (ADR 0042). The contract declares one failure for this read, and this is what it means.
     */
    public record NoSuchGroup(UUID groupId) implements DomainError {

        public static final String CODE = "api.no_such_group";

        public NoSuchGroup {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "there is no group " + groupId + " that this caller is a member of. Either no group was created with "
                    + "that id, or the caller is not in it";
        }
    }

    /** A name the contract's {@code maxLength} refuses, refused at the boundary that promised the length. */
    public record NameTooLong(int limit) implements DomainError {

        public static final String CODE = "api.group_name_too_long";

        public NameTooLong {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "a group's name may be at most " + limit + " characters, which is what the contract promises and what "
                    + "the column's constraint enforces";
        }
    }

    /**
     * A timezone that is not an IANA zone.
     *
     * <p>Refused rather than stored, because a zone that no calendar can resolve is worse than no zone at all: the column
     * is nullable and a group without one knows it has not chosen, while a group holding {@code "IST"} believes it has
     * answered a question that decides which cycle an obligation falls in.
     */
    public record UnknownTimezone(String timezone) implements DomainError {

        public static final String CODE = "api.group_unknown_timezone";

        public UnknownTimezone {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "'" + timezone + "' is not an IANA time zone, so no cycle boundary can be derived from it. "
                    + "'Asia/Kolkata' is a zone; 'IST' is an abbreviation three countries share";
        }
    }
}
