package io.reductxn.kernel.api;

/**
 * A domain failure with a stable, machine-readable code.
 *
 * <p>Expected failures are values, not exceptions. Only genuine programming faults throw.
 */
public interface DomainError {

    /** Stable identifier, safe to expose to clients and to log. Never localised. */
    String code();

    /** Human-readable description, for logs. Not part of any API contract. */
    String message();
}
