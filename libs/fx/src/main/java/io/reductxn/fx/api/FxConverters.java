package io.reductxn.fx.api;

import io.reductxn.fx.internal.ScaledFxConverter;

/**
 * Public factories for converters (RULE B10).
 *
 * <p>As with netting engines and tax rules, the implementation stays private and callers see only the
 * interface. That is what makes "public surface" a real thing rather than a folder convention.
 */
public final class FxConverters {

    /**
     * Shared instance: the converter is stateless, so there is nothing to gain from constructing one
     * per call and something to lose in garbage on a settlement hot path.
     */
    private static final FxConverter EXACT = new ScaledFxConverter();

    private FxConverters() {
    }

    /**
     * The exact scaled-integer converter.
     *
     * <p>There is only one, and there is deliberately no floating-point alternative to reach for. A
     * second implementation would be a second rounding rule, and the whole point of P12 is that money
     * arithmetic has exactly one.
     */
    public static FxConverter exact() {
        return EXACT;
    }
}
