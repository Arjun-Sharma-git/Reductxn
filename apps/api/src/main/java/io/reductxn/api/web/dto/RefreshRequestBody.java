package io.reductxn.api.web.dto;

/**
 * The contract's `auth/refresh` body, which this slice added: the refresh token, so rotation has something to rotate.
 *
 * <p><strong>Why this schema was needed and what the alternative was.</strong> The operation declared no body and
 * {@code security: []} - the OpenAPI way of saying "the bearer token does not authenticate this" - so there was nowhere
 * for the refresh token to travel. Interpreting the {@code Authorization} header as an access token on some paths and a
 * refresh token on others was the alternative, and it would make one header mean two things depending on the URL, which is
 * the kind of cleverness that ends with a client sending the wrong one and a server accepting it (ADR 0042).
 */
public record RefreshRequestBody(String refreshToken) {
}
