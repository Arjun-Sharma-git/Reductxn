package io.reductxn.api.web.dto;

import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetDirection;
import io.reductxn.netting.api.NetPosition;

import java.util.UUID;

/**
 * The {@code NetPosition} schema from {@code contracts/openapi/reductxn-api.yaml}.
 *
 * <p>Top-level rather than nested, because the contract makes it top-level: the same schema is one entry of
 * {@code GET /groups/{groupId}/balances} and one entry of the preview's {@code bestCase.positions}. It was nested
 * inside {@link NettingPreviewResponse} while the only endpoint that answered positions was the preview, which is a
 * Java-side shape the document never had - and moving it to where the contract already put it is why this read, when
 * it arrived, did not need a second record with the same three fields (ADR 0039).
 *
 * <p>{@code direction} is a word - {@code pay}, {@code receive}, {@code flat} - and it is stated here rather than
 * derived from the enum's name. Deriving it would need a locale to be correct, and a rename inside
 * {@code libs/netting} would silently change a wire value; the exhaustive switch makes that a compile error.
 */
public record NetPositionResponse(UUID userId, String direction, MoneyDto net) {

    /** One position off the engine, as the document describes it. */
    public static NetPositionResponse of(NetPosition position) {
        return new NetPositionResponse(
                position.userId(),
                directionName(position.direction()),
                MoneyDto.of(position.net()));
    }

    /** Same reasoning as the mapping in {@code NettingPreviewMapper}: the wire value is stated, not derived. */
    private static String directionName(NetDirection direction) {
        return switch (direction) {
            case PAY -> "pay";
            case RECEIVE -> "receive";
            case FLAT -> "flat";
        };
    }
}
