package io.reductxn.kernel.api;

import java.util.function.Function;

/**
 * Explicit success-or-failure carrier for domain operations.
 *
 * <p>The domain core never throws for an expected failure, so a caller cannot silently forget to
 * handle it. This keeps boundary code honest without exceptions as control flow.
 *
 * @param <T> the success value type
 */
public sealed interface Result<T> permits Result.Ok, Result.Failure {

    /** Success. */
    record Ok<T>(T value) implements Result<T> {}

    /** Expected failure. */
    record Failure<T>(DomainError error) implements Result<T> {}

    static <T> Result<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Result<T> fail(DomainError error) {
        return new Failure<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    default boolean isFailure() {
        return this instanceof Failure<T>;
    }

    /**
     * Unwraps the success value.
     *
     * @throws IllegalStateException if this is a failure - i.e. a caller ignored a Result
     */
    default T orElseThrow() {
        if (this instanceof Ok<T> success) {
            return success.value();
        }
        throw new IllegalStateException(
                "unhandled domain error: " + ((Failure<T>) this).error().code());
    }

    default <R> Result<R> map(Function<? super T, ? extends R> mapper) {
        if (this instanceof Ok<T> success) {
            return new Ok<>(mapper.apply(success.value()));
        }
        return new Failure<>(((Failure<T>) this).error());
    }
}
