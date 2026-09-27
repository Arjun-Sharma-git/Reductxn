package io.reductxn.api.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.util.Objects;

/**
 * A rail's callback that could not be acted on, as values.
 *
 * <p><strong>Two codes, and the split is what a rail can do about them.</strong> An unverified callback is a
 * {@code 401}: the signature did not match the deployment's secret, so nothing about the request is trusted - not its
 * payload, not the provider in its path - and a rail whose secret has drifted must be told by an operator rather than
 * by retrying. A callback whose *payload* cannot be understood is a {@code 400}: it was signed by somebody who holds
 * the secret, so the problem is the body, and the answer can say which part of it.
 *
 * <p><strong>Why an invalid signature is a {@code DomainError} at all.</strong> It is a web-layer fact rather than a
 * domain one, and this class says so - but the refusal still travels as a code, because a rail integrating with this
 * deployment needs to branch on it and the alternative is that it parses prose. {@code SignInRefusals.NotSignedIn} is
 * the same shape for the same reason (ADR 0042).
 *
 * <p><strong>What is deliberately not refused.</strong> A callback naming a reference no transfer was ever offered
 * under is recorded like any other. The table is an inbox keyed by the reference, nothing reads a row whose reference
 * the worker never asks about, and the alternative - the API checking whether the worker's transfer exists - would be
 * this module forming an opinion about another module's rows to write a row of its own. The worker's test asserts the
 * part that matters: a callback about a transfer the rail never saw cannot move money (ADR 0051).
 */
public final class RailCallbackRefusals {

    private RailCallbackRefusals() {
    }

    /**
     * The signature did not verify, so nothing in the request is trusted.
     *
     * <p>Reached by a missing header, a wrong secret, or a body altered after signing. All three are one code because
     * the caller's next step is the same in all three cases, and because saying which one it was would tell somebody
     * probing this endpoint which half of their guess was right.
     */
    public record InvalidSignature(String provider) implements DomainError {

        public static final String CODE = "api.invalid_signature";

        public InvalidSignature {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(provider, "provider");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "the callback for rail " + provider + " did not carry a signature this deployment accepts. The body "
                    + "is signed with a shared secret, and nothing about a request whose signature does not verify is "
                    + "trusted - including the provider in its path";
        }
    }

    /**
     * The callback was signed, and cannot be understood: a missing field, or a state this build does not know.
     *
     * <p><strong>A state this build has not heard of is refused rather than stored.</strong> The rail's word is what
     * the driver acts on, so a word it cannot map would be a row nothing can use - and storing it would look like an
     * answer had arrived. The four words this build knows are the contract's, and a rail that adds a fifth gets a
     * {@code 400} naming it: that is a deployment to update, not a transfer to move on a guess.
     */
    public record NotUnderstood(String field, String value) implements DomainError {

        public static final String CODE = "api.callback_not_understood";

        public NotUnderstood {
            DomainCodes.requireWellFormed(CODE);
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "this callback's " + field + " is \"" + value + "\", which this build cannot act on: a callback is "
                    + "the rail's own word about a transfer, and a field nothing can map would be an answer that "
                    + "reached the rows as a question";
        }
    }
}
