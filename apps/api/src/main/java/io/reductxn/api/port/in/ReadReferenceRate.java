package io.reductxn.api.port.in;

import io.reductxn.fx.api.FxRate;
import io.reductxn.kernel.api.Result;

/**
 * The use case: what a pair converts at, right now, for a screen about to record a cross-currency commitment.
 *
 * <p><strong>It answers {@code Result} because two of its three answers are refusals</strong>, and neither is a
 * fault: a pair with no rate at all and a pair whose only rate has expired are both things this deployment knows and
 * can say. They are not the same answer - one is "the server cannot answer yet, try later" and the other is "this
 * cannot be settled in until the feed catches up" - so they travel as the provider's own two codes rather than being
 * flattened into one.
 *
 * <p><strong>The rate it returns is the rate the recording path would lock.</strong> That is the decision (ADR 0037):
 * this read applies the same freshness policy as locking, so a screen showing the number is showing a rate a member
 * can actually get - and a pair that cannot be settled in is refused here rather than after the form is filled in.
 * It is still not the locked quote: the obligation's own instant decides which rate is locked, and a rate can expire
 * in between. "Indicative" is the honest word, and the response says when it was quoted so a screen can say it too.
 */
public interface ReadReferenceRate {

    /** The rate for a pair, as of now. */
    Result<FxRate> forPair(String base, String quote);
}
