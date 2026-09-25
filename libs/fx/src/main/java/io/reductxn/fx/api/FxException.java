package io.reductxn.fx.api;

import io.reductxn.kernel.api.DomainError;

import java.util.Objects;

/**
 * Base type for every FX failure.
 *
 * <p>Each subtype carries a stable, namespaced {@link #code()} alongside its English message. The
 * code is what a client branches on and localizes; the message is developer text for logs and support
 * (RULE B20, ADR 0014). Carrying the code on the exception means the API layer maps it to a problem
 * response without an intermediate translation table that could drift.
 *
 * <p>Codes are dotted and namespaced, matching the contract's examples. NOTE: the pre-existing
 * exceptions in {@code libs/ledger} and {@code libs/netting} predate this and return
 * {@code SCREAMING_SNAKE} codes, which do not match the contract's examples. One of the two has to
 * change, and the contract is authoritative (P5). Tracked as a follow-up rather than fixed silently
 * here, because {@code libs/ledger} carries the stricter review bar.
 */
public abstract class FxException extends RuntimeException implements DomainError {

    private static final long serialVersionUID = 1L;

    private final String code;

    protected FxException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    @Override
    public final String code() {
        return code;
    }

    @Override
    public final String message() {
        return getMessage();
    }
}
