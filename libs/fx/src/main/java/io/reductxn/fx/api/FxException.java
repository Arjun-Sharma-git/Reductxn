package io.reductxn.fx.api;

import io.reductxn.kernel.api.DomainCodes;
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
 * <p>Codes follow {@link DomainCodes}: lower case, dot-separated, namespaced by the owning module.
 * Each subtype declares its code as a {@code public static final String CODE} so it can be referenced
 * and enumerated without constructing an exception.
 *
 * <p>An earlier version of this comment claimed that the pre-existing exceptions in {@code libs/ledger}
 * <em>and</em> {@code libs/netting} returned {@code SCREAMING_SNAKE} codes. Only {@code libs/ledger} did;
 * {@code libs/netting} declares no exception type at all. Recording the correction because the wrong
 * version made the inconsistency look twice as large as it was, and a claim like that is cheap to
 * repeat and expensive to un-believe. {@code libs/ledger} now follows the convention too.
 */
public abstract class FxException extends RuntimeException implements DomainError {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * @param code a code following {@link DomainCodes}, validated here rather than trusted
     */
    protected FxException(String code, String message) {
        super(message);
        this.code = DomainCodes.requireWellFormed(Objects.requireNonNull(code, "code"));
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
