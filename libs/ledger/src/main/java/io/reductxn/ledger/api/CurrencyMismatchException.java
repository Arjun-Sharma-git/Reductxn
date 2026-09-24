package io.reductxn.ledger.api;

import io.reductxn.kernel.api.DomainError;

/**
 * Raised when arithmetic mixes two different currencies.
 *
 * <p>This is a programming fault rather than an expected failure, so it throws. An implementation
 * that silently coerced currencies would corrupt the ledger.
 */
public final class CurrencyMismatchException extends RuntimeException implements DomainError {

    private static final long serialVersionUID = 1L;

    private final String left;
    private final String right;

    public CurrencyMismatchException(Object left, Object right) {
        super("currency mismatch: " + left + " and " + right);
        this.left = String.valueOf(left);
        this.right = String.valueOf(right);
    }

    @Override
    public String code() {
        return "CURRENCY_MISMATCH";
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
