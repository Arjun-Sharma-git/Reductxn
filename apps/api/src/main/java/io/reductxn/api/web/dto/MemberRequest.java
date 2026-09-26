package io.reductxn.api.web.dto;

/**
 * The contract's `MemberRequest`: a membership a caller asks the group to record.
 *
 * <p>No validation here, deliberately. What counts as a malformed request is decided at the boundary - the
 * controller checks the uuid, the name's length and the role against the schema's vocabulary - because that is where
 * the contract is being honoured, and a DTO that validated would put a rule about the wire in a type that three
 * layers share.
 *
 * <p>`role` is optional in the contract because the ordinary member is the common case: absent means `member`, which
 * the controller spells out rather than the DTO defaulting it, so that \"the caller did not say\" and \"the caller
 * said member\" end up in the same place visibly.
 */
public record MemberRequest(String userId, String displayName, String role) {
}
