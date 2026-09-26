package io.reductxn.api.application.identity;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

/**
 * The one-time code's hash, and the salt it needs - one implementation, used by the service and by the tests.
 *
 * <p><strong>Why this code is slow to hash and a session token is not.</strong> A six-digit code is twenty bits: a
 * database leak of plain codes is a leak of every live code, and a thousand guesses a second would find one in minutes.
 * PBKDF2 with a per-challenge salt makes each guess cost what a password guess costs, which is the only thing that stands
 * between a leaked row and somebody's phone number. A session token is the opposite case - 256 random bits, nothing to
 * enumerate - and {@link SessionTokens} hashes it with plain SHA-256 for that reason.
 *
 * <p><strong>The iteration count is a constant rather than a property, for a reason worth stating.</strong> A cost that a
 * deployment can lower is a cost that will be lowered the first time a test is slow, and the security is exactly the
 * cost. It is here, next to the algorithm, so that changing it is a deliberate edit to the thing it protects.
 *
 * <p>{@link #matches} compares with {@link MessageDigest#isEqual} rather than {@code Arrays.equals}, because a comparison
 * that stops at the first differing byte tells an attacker how much of a guess was right.
 */
public final class OneTimeCodeHash {

    /** What each guess costs. Raising this is a decision, and the only reason to lower it is a benchmark. */
    private static final int ITERATIONS = 120_000;

    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int DIGITS = 6;

    private OneTimeCodeHash() {
    }

    /** A code a person can read off a phone: {@code DIGITS} digits, leading zeros included. */
    public static String code(SecureRandom random) {
        return String.format("%0" + DIGITS + "d", random.nextInt(1_000_000));
    }

    /** A fresh salt, per challenge. A shared salt would make one precomputed table worth building. */
    public static byte[] salt(SecureRandom random) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);

        return salt;
    }

    /** The hash of {@code code} under {@code salt}. */
    public static byte[] hash(String code, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(code.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS);

            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            } finally {
                // The spec holds the code as chars; clearing it is the one thing this class can do about that.
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException impossible) {
            // Neither can happen on a JDK that runs this application at all, and a hash that silently became something
            // else would be worse than a failure to start.
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", impossible);
        }
    }

    /** Whether {@code code} is the one that hashed to {@code expected} under {@code salt}. */
    public static boolean matches(String code, byte[] salt, byte[] expected) {
        return MessageDigest.isEqual(hash(code, salt), expected);
    }
}
