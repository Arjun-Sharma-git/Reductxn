package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.List;

/**
 * The member domain's expected failures, as values.
 *
 * <p>One type per code with {@code public static final String CODE}, for the reason {@link ObligationRefusals}
 * documents: {@code DomainErrorCodeTest} enumerates every concrete {@code DomainError} so the error-code parity gate
 * (I3) can list what the server may answer with.
 *
 * <p><strong>Only one code is new here, and the other refusal is borrowed.</strong> A missing or malformed field is
 * {@link RunRefusals.RequestIncomplete} - the code the runs and cycles endpoints already answer with - because a code
 * identifies a condition rather than an endpoint (RULE B20), and `api.request_incomplete` means \"something is
 * missing from the request\" whoever is asking. A name that is too long is its own code, mirroring the obligation
 * memo: refused rather than truncated, since a truncated name is a fact about a person that this API invented.
 */
public final class MemberRefusals {

    private MemberRefusals() {
    }

    /** A name this API will not store. Refused rather than cut to length. */
    public record NameTooLong(int length, int maxLength) implements DomainError {

        public static final String CODE = "api.member_name_too_long";

        public NameTooLong {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the member's display name is " + length + " characters, and this API stores at most " + maxLength
                    + ". A name cut to length would be a name the group did not choose";
        }
    }

    /** The roles this API stores, as the contract's schema and the table's CHECK agree on them. */
    public static final List<String> ROLES = List.of("admin", "member");
}
