package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;

import java.time.Instant;
import java.util.UUID;

/**
 * The run domain's expected failures, as values.
 *
 * <p>One type per code, each declaring {@code public static final String CODE}, because
 * {@code DomainErrorCodeTest} requires it of every concrete {@code DomainError} - the error-code parity gate
 * (I3) has to be able to enumerate every code the server can emit.
 *
 * <p><strong>The namespace is {@code settlement}, and it agrees with the contract here.</strong> In
 * {@code apps/api} the two written rules disagreed, because one deployable owns several domains and the
 * mechanical rule derives the namespace from the package segment. This module owns exactly one domain and its
 * package segment is {@code settlement}, so the contract's reserved {@code settlement.*} namespace and the
 * guard's derivation produce the same string - and {@code settlement.fx_unfunded}, the code MASTER-PLAN names as
 * the funding gate's, would land here when the gate itself is enforced.
 */
public final class RunRefusals {

    private RunRefusals() {
    }

    /**
     * The cycle contains no obligations, so there is nothing to settle.
     *
     * <p>A refusal rather than an empty run, and the difference is not cosmetic. An empty plan settles nothing
     * and zeroes nothing, but it *looks* like a completed cycle: a run in {@code awaiting_approval} with no
     * partitions would let a group approve a settlement that does not exist, and its positions would then be
     * "settled" in the only place that records them. Refusing says what is true - there was nothing to do -
     * rather than recording a decision that was never made.
     */
    public record NothingToSettle(UUID groupId, Instant asOf) implements DomainError {

        public static final String CODE = "settlement.nothing_to_settle";

        public NothingToSettle {
            DomainCodes.requireWellFormed(CODE);
        }

        @Override
        public String code() {
            return CODE;
        }

        @Override
        public String message() {
            return "group " + groupId + " has no open obligations as of " + asOf + ", so there is no cycle to "
                    + "settle. An empty run would record a settlement of nothing and read as a completed cycle";
        }
    }
}
