package io.reductxn.api.adapter.rate;

import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.FxRateProvider;
import io.reductxn.fx.api.UnavailableRateException;
import io.reductxn.ledger.api.Currency;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * The rate provider this deployment has: none.
 *
 * <p>It fails closed, and that is the design rather than a placeholder. {@code FxRateProvider}'s own
 * documentation forbids returning a placeholder, a rate of one, or a cached value of unknown age in place of
 * failing - "a guessed rate propagates into real transfers and is an accounting incident, whereas an
 * exception is only an availability incident" - and MASTER-PLAN section 4 states the same thing from the
 * product's side: no rate provider on the write path means no cross-currency obligations, not cross-currency
 * obligations at a made-up rate.
 *
 * <p>So a cross-currency request is answered {@code 503} with {@code fx.rate_unavailable}, and the client can
 * retry the day a provider is configured. The slice that supplies one has to bring the quote's storage with
 * it: {@code RecordObligationService} explains why, and it is a landmine worth reading before wiring a
 * provider to this port.
 *
 * <p>Deliberately not a bean that Spring can replace by accident. It is the only implementation of the port
 * in this module, so a deployment that has a rate source has to delete this class to add it - which is a
 * failing build rather than two beans and an ordering question.
 */
@Component
public class UnconfiguredRateProvider implements FxRateProvider {

    @Override
    public FxRate rateFor(Currency base, Currency quote, Instant asOf) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(asOf, "asOf");

        throw new UnavailableRateException(base, quote, asOf,
                "this deployment has no rate source. no rate provider is configured, so no cross-currency "
                        + "obligation can be recorded: refusing rather than quoting at a rate nobody "
                        + "published (ADR 0013 point 6)");
    }

    @Override
    public String name() {
        return "unconfigured";
    }
}
