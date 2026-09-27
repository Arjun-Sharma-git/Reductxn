package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.NewRailCallback;
import io.reductxn.api.port.in.RecordedRailCallback;

import java.time.Instant;
import java.util.Objects;

/**
 * The contract's shapes for a rail callback: the body it sends, and the acknowledgement's receipt.
 *
 * <p><strong>Two directions in one class, as {@code DecisionMapper} does, because they are the same three facts.</strong>
 * What arrives is the reference, the rail's word and the rail's own text; what is acknowledged is the reference, the
 * word as recorded, and when. The provider and the instant are added from outside the body - the path and the clock -
 * which is exactly what a mapper exists for and why a DTO in {@code web/dto} is not the command in {@code port.in}.
 */
public final class RailCallbackMapper {

    private RailCallbackMapper() {
    }

    /** The command, with the two facts the body does not carry: which rail this was, and when it arrived. */
    public static NewRailCallback toCommand(String provider, RailCallbackRequest request, Instant receivedAt) {
        return new NewRailCallback(
                Objects.requireNonNull(provider, "provider"),
                request.railRef(),
                request.state(),
                request.detail(),
                Objects.requireNonNull(receivedAt, "receivedAt"));
    }

    public static RailCallbackResponse toResponse(RecordedRailCallback recorded) {
        return new RailCallbackResponse(
                recorded.provider(), recorded.railRef(), recorded.state(), recorded.receivedAt());
    }
}
