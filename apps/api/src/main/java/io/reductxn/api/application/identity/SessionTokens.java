package io.reductxn.api.application.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Session tokens: how one is drawn, and how it is stored.
 *
 * <p><strong>Opaque and random rather than a JWT, and SHA-256 rather than PBKDF2.</strong> A token is 256 bits from a
 * secure random source - nothing to enumerate, no dictionary, so a fast hash is the right hash: what a slow hash buys is
 * protection against guessing, and there is nothing here to guess. The contract's {@code bearerAuth} used to say
 * {@code bearerFormat: JWT} and no longer does (ADR 0042): a JWT would need a signing key, a rotation story for that key,
 * and it would still need a row for `POST /auth/refresh` to rotate, so statelessness would buy one indexed lookup at the
 * cost of a key management problem this build does not have.
 *
 * <p><strong>{@code URL_SAFE} base64 without padding</strong>, because the token travels in an {@code Authorization}
 * header and in JSON: a token with a {@code +} in it is a token somebody eventually mis-quotes.
 */
public final class SessionTokens {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionTokens() {
    }

    /** A token, drawn fresh. Never stored; only its hash is. */
    public static String issue() {
        byte[] token = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(token);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** The hash the session table holds in place of the token. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(token)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** UTF-8 bytes, in one place, so the hash and any future comparison agree about encoding. */
    public static byte[] utf8(String token) {
        return token.getBytes(StandardCharsets.UTF_8);
    }
}
