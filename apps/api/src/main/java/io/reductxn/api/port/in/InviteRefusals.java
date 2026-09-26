package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

/**
 * The invite domain's expected failures, as values.
 *
 * <p>One type per code with {@code public static final String CODE}, for the reason {@link ObligationRefusals}
 * documents: {@code DomainErrorCodeTest} enumerates every concrete {@code DomainError} so the error-code parity gate
 * (I3) can list what the server may answer with, and the client's error catalogue has a sentence for each one.
 *
 * <p><strong>Expired and exhausted are two codes under one status, and both halves of that are deliberate.</strong> The
 * contract answers {@code 410} for "invite expired or exhausted" - one status, because a client's remedy is the same
 * either way: ask somebody for a new code. The codes stay distinct because a code identifies a *condition* rather than a
 * response (RULE B20), and the two conditions differ in the one way a reader cares about: an expired invite runs out of
 * time and an exhausted one runs out of uses, and "ask for a new one" is a different sentence from "this one was already
 * used". A single {@code api.invite_not_usable} would have made the client say neither.
 *
 * <p>A missing or malformed field is {@link RunRefusals.RequestIncomplete} and a name that is too long is
 * {@link MemberRefusals.NameTooLong} - the codes those conditions already have, because a code is contract surface and
 * two codes for one condition would be two identities for it (RULE B20). Accepting a code is a way of becoming a member,
 * so it refuses a name by the same rule the members write uses.
 */
public final class InviteRefusals {

    private InviteRefusals() {
    }

    /** There is no invitation with this code. */
    public record NoSuchInvite(String code) implements DomainError {

        public static final String CODE = "api.no_such_invite";

        public NoSuchInvite {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "no invitation has the code " + code + ". A code identifies an invitation and the group it joins - "
                    + "a mistyped one is a code for nothing rather than for the wrong group";
        }
    }

    /** The invitation existed and its window has closed. */
    public record InviteExpired(String code) implements DomainError {

        public static final String CODE = "api.invite_expired";

        public InviteExpired {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the invitation " + code + " has expired, so it no longer joins anybody. Ask the group for a "
                    + "current code";
        }
    }

    /** The invitation existed, its window is open, and it has been spent. */
    public record InviteExhausted(String code) implements DomainError {

        public static final String CODE = "api.invite_exhausted";

        public InviteExhausted {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the invitation " + code + " has been used as many times as it allows, so it no longer joins "
                    + "anybody. Ask the group for a current code";
        }
    }
}
