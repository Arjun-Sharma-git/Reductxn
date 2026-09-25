package io.reductxn.ledger.api;

import io.reductxn.kernel.api.DomainError;

/**
 * Raised when arithmetic mixes two different currencies.
 *
 * <p>This is a programming fault rather than an expected failure, so it throws. An implementation
 * that silently coerced currencies would corrupt the ledger.
 *
 * <p>Its code is namespaced like every other: {@link #CODE} is {@code ledger.currency_mismatch}. It
 * used to return the bare {@code CURRENCY_MISMATCH}, which was not off by a style rule - an unnamespaced
 * code cannot be attributed to a module, cannot be checked for collision, and cannot be enumerated by
 * the error-code parity gate (I3). See {@link io.reductxn.kernel.api.DomainCodes}.
 */
public final class CurrencyMismatchException extends RuntimeException implements DomainError {

    private static final long serialVersionUID = 1L;

    /**
     * The stable code for this failure.
     *
     * <p>A constant rather than a literal inside {@link #code()} so it can be referenced without
     * constructing an exception and enumerated reflectively, which is what lets
     * {@code DomainErrorCodeTest} check that the convention holds.
     */
    public static final String CODE = "ledger.currency_mismatch";

    private final String left;
    private final String right;

    public CurrencyMismatchException(Object left, Object right) {
        super("currency mismatch: " + left + " and " + right);
        this.left = String.valueOf(left);
        this.right = String.valueOf(right);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String message() {
        return getMessage();
    }

    public String left() {
        return left;
    }

    public String right() {
        return right;
    }
}
