package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * A rail's callback, as the boundary understood it: the rail's word, and the reference it is about.
 *
 * <p><strong>It carries no signature and no raw bytes.</strong> Verification happens before this exists - the controller
 * checks the signature against the deployment's secret and refuses a callback that does not carry one - so what arrives
 * here has already been established as coming from the rail the path names. Keeping the wire-level facts out of the use
 * case's vocabulary is the same split {@code DecisionRequest} makes: the boundary parses and verifies, the use case
 * decides, and neither can do the other's job by accident.
 *
 * @param provider   which rail called back, from the path
 * @param railRef    the reference the transfer was offered under (RULE S2), which is what makes the answer findable
 * @param state      the rail's word for what happened, already checked against the four this build knows
 * @param detail     the rail's own words, kept because they are the only account of *why* (never derived here)
 * @param receivedAt when the callback was received, from the boundary's clock (RULE B21)
 */
public record NewRailCallback(String provider, String railRef, String state, String detail, Instant receivedAt) {

    public NewRailCallback {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(receivedAt, "receivedAt");
        // `detail` is deliberately not required: a rail that says only "confirmed" has still said all it needs to, and
        // refusing it for saying little would be this build inventing a requirement the rail never agreed to.
    }
}
