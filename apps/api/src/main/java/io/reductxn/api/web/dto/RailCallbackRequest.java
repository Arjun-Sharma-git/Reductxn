package io.reductxn.api.web.dto;

/**
 * A rail's callback as it arrives in a request body, per the contract.
 *
 * <p><strong>A DTO of its own rather than the use case's command.</strong> The command carries the provider (from the
 * path) and the instant (from the boundary's clock), neither of which is in the body - and a request shape that doubles
 * as a command is how a boundary's vocabulary leaks into a use case. {@code DecisionRequest} is the same split for the
 * same reason.
 *
 * <p><strong>The fields are unvalidated here.</strong> They are checked where every other refusal is decided - in the
 * use case and its mapper - so that a callback with an unknown state and one with an empty reference are refused by the
 * same code path and reported the same way, rather than by a second mechanism this class would need.
 *
 * @param railRef the reference the transfer was offered under (RULE S2)
 * @param state   the rail's word: {@code accepted}, {@code pending}, {@code confirmed} or {@code failed}
 * @param detail  the rail's own words about why, which are stored as they arrived and never interpreted
 */
public record RailCallbackRequest(String railRef, String state, String detail) {
}
