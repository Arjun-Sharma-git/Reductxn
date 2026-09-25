package io.reductxn.kernel.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The error-code naming convention, stated once and checkable in one place.
 *
 * <p>A code is what a client branches on and localizes, so it is contract surface (P5, RULE B20). It
 * must be stable, and it must be unambiguous - which means it needs a namespace. Two modules that each
 * define {@code MISMATCH} produce a client catalog with one key and two meanings, and nothing
 * detects it.
 *
 * <p>The convention, established by the {@code fx.*} codes and now applied everywhere:
 *
 * <pre>
 *   fx.rate_stale
 *   ledger.currency_mismatch
 *   settlement.fx_unfunded
 * </pre>
 *
 * <p>Segments are lower case, dot-separated, and there is always at least one dot. The first segment
 * is the owning domain module, which is what makes a code attributable and what lets a test check that
 * a code belongs to the module declaring it rather than trusting it does.
 *
 * <p>Before this existed the codes disagreed: {@code libs/fx} returned dotted names and
 * {@code libs/ledger} returned {@code CURRENCY_MISMATCH}. The contrast was not cosmetic. Without a
 * namespace there is nothing preventing a collision, and the error-code parity gate (I3) needs a
 * canonical list of server codes to check a client catalog against - which needs codes that can be
 * enumerated, not strings buried in method bodies.
 */
public final class DomainCodes {

    /** Lower case, dot-separated, at least two segments, each segment starting with a letter. */
    private static final Pattern WELL_FORMED = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_]*)+");

    private DomainCodes() {
    }

    /**
     * Returns the code unchanged, or throws if it does not follow the convention.
     *
     * <p>Deliberately a throw rather than a boolean: a malformed code is a programming fault that
     * should surface on the first construction, in a test, rather than reach a client.
     *
     * @return the same code, so a constructor can write {@code this.code = requireWellFormed(code)}
     */
    public static String requireWellFormed(String code) {
        Objects.requireNonNull(code, "code");
        if (!WELL_FORMED.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "error code '" + code + "' is not well formed. Expected lower-case, "
                            + "dot-separated segments with at least one namespace, "
                            + "for example fx.rate_stale or ledger.currency_mismatch");
        }
        return code;
    }

    /**
     * The namespace of a code: the first segment, which names the owning domain module.
     *
     * @throws IllegalArgumentException if the code is not well formed
     */
    public static String namespaceOf(String code) {
        requireWellFormed(code);
        return code.substring(0, code.indexOf('.'));
    }
}
