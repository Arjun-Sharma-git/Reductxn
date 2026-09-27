package io.reductxn.api.application.rails;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifying that a callback came from the rail it claims to be.
 *
 * <p><strong>HMAC-SHA256 over the raw bytes of the body, with a secret the deployment holds.</strong> Not a hash of the
 * parsed fields, and not a hash of a re-serialisation: the signature covers the bytes the rail sent, so it is checked
 * before anything parses them and a body altered in transit cannot verify. That is also why the controller takes the
 * body as bytes and reads it only afterwards.
 *
 * <p><strong>An unset secret refuses every callback, and that is the fail-closed default.</strong> A deployment that has
 * not been told what its rail signs with cannot tell a callback from anybody else's POST, so the honest answer is
 * {@code 401} for all of them - the same shape {@code reductxn.rates.feed} has, where an unset provider means nothing
 * is ingested rather than everything is accepted (ADR 0027). A rail that calls back into a deployment with no secret
 * configured is a deployment that has not been finished, and it says so in the response rather than by moving money.
 *
 * <p><strong>The comparison is constant-time</strong> ({@link MessageDigest#isEqual}), because a byte-by-byte comparison
 * that stopped at the first difference would leak how much of a guessed signature was right - which is the one thing
 * an attacker needs to forge one.
 *
 * <p>The header format is {@code sha256=<lowercase hex>}. It is stated in the contract, because it is a promise about
 * what this deployment accepts rather than an implementation detail.
 */
@Component
public class RailCallbackSignature {

    /** The header a rail signs with. Named here and in the contract, and nowhere else. */
    public static final String HEADER = "X-Rail-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public RailCallbackSignature(@Value("${reductxn.rails.webhook-secret:}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Whether this body was signed with this deployment's secret.
     *
     * @param body      the raw bytes as they arrived, before parsing
     * @param signature the header as the rail sent it, or null when it sent none
     * @return true only when the header carries a digest of these exact bytes under the configured secret
     */
    public boolean verifies(byte[] body, String signature) {
        if (secret.length == 0 || signature == null || !signature.startsWith(PREFIX)) {
            return false;
        }

        byte[] offered;

        try {
            offered = HexFormat.of().parseHex(signature.substring(PREFIX.length()));
        } catch (IllegalArgumentException notHex) {
            // A signature that is not hex is not a signature this build issued, and answering "no" here is the same
            // answer as a wrong digest - see the refusal's javadoc for why the two are not told apart.
            return false;
        }

        return MessageDigest.isEqual(digest(body), offered);
    }

    /** What a rail's signature should be for this body and this secret. */
    private byte[] digest(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));

            return mac.doFinal(body);
        } catch (Exception impossible) {
            // `HmacSHA256` is in every JRE this build runs on and the key is never empty here, so this cannot happen:
            // an exception rather than a refusal, because a deployment whose crypto is missing is broken rather than
            // being told "no" by a rail.
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
