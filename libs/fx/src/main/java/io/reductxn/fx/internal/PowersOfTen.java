package io.reductxn.fx.internal;

/**
 * Powers of ten as exact {@code long} values.
 *
 * <p>Exists so the conversion never computes a power of ten with floating point, which is the one
 * place a {@code double} would sneak back into an otherwise exact calculation (P12).
 *
 * <p>Bounds are enforced rather than assumed: the conversion's denominator exponent is at most
 * {@code 2 * FxRate.MAX_SCALE} = 16, and asking for more than {@link #MAX_EXPONENT} throws instead of
 * silently wrapping a {@code long}.
 */
public final class PowersOfTen {

    /** 10^18, the largest power of ten that fits in a signed 64-bit integer. */
    public static final int MAX_EXPONENT = 18;

    private static final long[] VALUES = new long[MAX_EXPONENT + 1];

    static {
        long value = 1L;
        for (int exponent = 0; exponent <= MAX_EXPONENT; exponent++) {
            VALUES[exponent] = value;
            // Guarded so the final iteration does not compute 10^19 and wrap silently. The table
            // entries themselves are all computed before that would happen, but an intentional
            // overflow is not something to leave lying in a static initialiser.
            if (exponent < MAX_EXPONENT) {
                value *= 10L;
            }
        }
    }

    private PowersOfTen() {
    }

    /**
     * @throws IllegalArgumentException when the exponent is negative or above {@link #MAX_EXPONENT}
     */
    public static long of(int exponent) {
        if (exponent < 0 || exponent > MAX_EXPONENT) {
            throw new IllegalArgumentException(
                    "exponent out of range 0.." + MAX_EXPONENT + ": " + exponent);
        }
        return VALUES[exponent];
    }
}
