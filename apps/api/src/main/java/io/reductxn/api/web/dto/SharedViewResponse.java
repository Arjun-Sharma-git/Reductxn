package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.reductxn.api.port.in.InvitationGrant;
import io.reductxn.api.port.in.SharedView;
import io.reductxn.netting.api.NetPosition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The contract's `SharedView` schema: what a share link shows.
 *
 * <p><strong>{@code NON_NULL} for the one absence the schema allows</strong> - a position whose id the group's
 * membership does not name carries no `displayName`, which is a fact about the id rather than a missing field (the
 * currency clearing account is not a person, and a member who has left is not one the group names any more). Everything
 * else here is required by the schema, and a record that omitted any of it would be answering a narrower question than
 * the document promises.
 *
 * <p>{@code generatedAt} is the server's instant rather than the reader's, which is the whole reason it is on the wire:
 * a link can be opened days after it was made, so the answer says which "now" it computed at.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharedViewResponse(
        String code,
        Instant expiresAt,
        Instant generatedAt,
        Group group,
        List<SharedPosition> positions) {

    /** The group a link is for: its id always, its name when it has one, and how many members it has. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Group(UUID id, String name, int memberCount) {
    }

    /**
     * One position, with what the group calls the member.
     *
     * <p>It is flat rather than nesting the balances read's `NetPosition`, because that is the shape the schema
     * declares: `{memberId, displayName, direction, net}`. The direction word and the money come from
     * {@link NetPositionResponse#of}, so the enum-to-wire mapping stays in the one place that states it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SharedPosition(UUID memberId, String displayName, String direction, MoneyDto net) {

        static SharedPosition of(SharedView.SharedPosition position) {
            NetPositionResponse balance = NetPositionResponse.of(position.position());

            return new SharedPosition(
                    balance.userId(), position.displayName(), balance.direction(), balance.net());
        }
    }

    public static SharedViewResponse of(SharedView view) {
        return new SharedViewResponse(
                view.code(),
                view.expiresAt(),
                view.generatedAt(),
                new Group(view.group().id(), view.group().name(), view.group().memberCount()),
                view.positions().stream().map(SharedPosition::of).toList());
    }
}