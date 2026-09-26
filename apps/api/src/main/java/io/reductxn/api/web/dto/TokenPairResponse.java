package io.reductxn.api.web.dto;

import java.util.UUID;

/**
 * The contract's `TokenPair`: what a sign-in hands back.
 *
 * <p>No DTO validation and no shape of its own beyond the three fields the schema requires - {@code expiresIn} is seconds,
 * which the contract says in a description rather than in a format, so a client has to be told. The tokens travel in JSON
 * once and are never stored by the server: the session table holds hashes.
 */
public record TokenPairResponse(String accessToken, String refreshToken, int expiresIn) {
}
