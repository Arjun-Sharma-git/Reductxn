package io.reductxn.api.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * The bearer token out of a request, in one place.
 *
 * <p><strong>One reader, because two would disagree about the awkward cases.</strong> The header is
 * {@code Authorization: Bearer <token>}, and the cases that matter are a missing header, a header with another scheme, an
 * empty token, and the scheme's capitalisation - which RFC 7235 says is case-insensitive, so it has to be compared that
 * way. A controller that did this itself would get one of those differently from the next controller, and the difference
 * would be a request accepted by one endpoint and refused by another.
 *
 * <p><strong>The case-insensitive comparison is {@link String#regionMatches}, not a lower-cased copy.</strong> That is not
 * style: RULE B20 forbids this module from depending on {@code java.util.Locale} at all - a server that cannot see a
 * locale cannot render one - and {@code toLowerCase()} without a locale is exactly the wrong thing in a Turkish locale.
 * {@code regionMatches(true, ...)} compares case-insensitively without a locale existing, which is the property the rule
 * is asking for.
 *
 * <p><strong>A missing header is not a malformed request.</strong> This returns empty, and the use case answers "not
 * signed in" - a {@code 401} rather than a {@code 400}, because nothing is wrong with the request's shape: what is absent
 * is the credential, and the contract's answer for that is to sign in.
 */
public final class BearerToken {

    private static final String SCHEME = "Bearer ";

    private BearerToken() {
    }

    /** The token this request carries, or empty when it carries none this product will read. */
    public static Optional<String> from(HttpServletRequest request) {
        String header = request.getHeader("Authorization");

        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return Optional.empty();
        }

        String token = header.substring(SCHEME.length()).strip();

        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
