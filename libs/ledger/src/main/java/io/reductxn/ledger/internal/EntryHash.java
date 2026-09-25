package io.reductxn.ledger.internal;

import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Posting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The canonical encoding of a journal entry, and its SHA-256 digest (invariant L4).
 *
 * <p>The chain is the ledger's tamper evidence, so this encoding is the thing that has to be right. Two
 * properties matter, and the second is the one usually missed.
 *
 * <p><strong>Deterministic:</strong> the same entry content always produces the same bytes, so a chain
 * written last year verifies today. Every field is written explicitly, in a fixed order, with no
 * iteration over an unordered collection anywhere.
 *
 * <p><strong>Injective:</strong> two different entries cannot produce the same bytes. Free-form fields -
 * the currency code, the idempotency key, the previous hash - are <em>length-prefixed</em>
 * ({@code 7:reductxn}), so a value cannot impersonate a field boundary. Writing those fields bare with a
 * separator would let an idempotency key containing {@code "\nprevHash="} collide with a genuinely
 * different entry: a collision <em>constructed</em> rather than found, which defeats the point of
 * hashing. The remaining fields need no prefix because their shape is fixed - a UUID is always 36
 * characters, an enum is always one of a closed set, an integer is always digits - so the encoding is
 * parseable and therefore injective.
 *
 * <p>Nulls are written as a bare {@code -}, which cannot be mistaken for a length-prefixed value because
 * every length-prefixed value contains a colon.
 *
 * <p>SHA-256 rather than a faster digest: this runs in a verification job, not on the write path, and the
 * job's value is that nobody can produce a second preimage cheaply. Changing anything here - the digest,
 * the version tag, the field order - invalidates every existing chain, which is what the version tag at
 * the head of the encoding is for.
 *
 * <p>This package is not public surface (RULE B10). The canonical form is exposed so that the encoding
 * and its injectivity can be asserted directly; callers outside this module use
 * {@link JournalEntry#hash()} and never need to know how the bytes are laid out.
 */
public final class EntryHash {

    /** The digest algorithm. Named here so the choice is one edit, not a hunt. */
    private static final String ALGORITHM = "SHA-256";

    /**
     * The encoding version, written as the first line.
     *
     * <p>A stored hash cannot say which encoding produced it, so the encoding says so itself. Without
     * this, a change to the canonical form would silently invalidate every existing chain with no way to
     * tell an old-but-valid hash from a corrupted one.
     */
    private static final String VERSION = "reductxn.ledger.entry.v1";

    private static final int HEX_LENGTH = 64;

    /**
     * The number of characters in a rendered hash: two per byte of a 32-byte digest.
     *
     * <p>A method rather than the constant it returns, and that is not style. {@code javac} inlines a
     * compile-time constant, so a reference to one from another module leaves <em>no trace in the
     * bytecode</em> - and a dependency rule checks bytecode, so RULE B10 would report green while an
     * outsider reached into this package. Verified by sabotage: a field reference passed the rule, and a
     * call to this method failed it. Returning the value through a method keeps everything in this
     * package visible to the rule whose job is to keep it private.
     */
    public static int hexLength() {
        return HEX_LENGTH;
    }

    /** Written in place of a null free-form field; never valid as a length-prefixed value. */
    private static final String NULL = "-";

    private EntryHash() {
    }

    /**
     * The digest of an entry, as lower-case hex.
     *
     * <p>Note that the entry's own stored hash is not an input. A hash cannot contain itself, which is
     * why {@link JournalEntry} derives this instead of holding it as a field: there is then no way to
     * construct an entry whose hash disagrees with its content.
     */
    public static String of(JournalEntry entry) {
        return digestOf(canonicalForm(entry));
    }

    /** The exact bytes that are hashed, as a string. Asserted directly by the property tests. */
    public static String canonicalForm(JournalEntry entry) {
        StringBuilder canonical = new StringBuilder(VERSION).append('\n');

        appendField(canonical, "id", entry.id());
        appendField(canonical, "groupId", entry.groupId());
        appendField(canonical, "currencyCode", entry.currency().code());
        appendField(canonical, "currencyExponent", Integer.toString(entry.currency().minorUnitExponent()));
        appendField(canonical, "occurredAt", entry.occurredAt().toString());
        appendField(canonical, "kind", entry.kind().name());
        appendField(canonical, "createdBy", entry.createdBy());
        appendField(canonical, "idempotencyKey", entry.idempotencyKey());
        appendField(canonical, "fxLinkId", entry.fxLinkId());
        appendField(canonical, "fxRole", entry.fxRole() == null ? null : entry.fxRole().name());
        appendField(canonical, "railPerformedFx", Boolean.toString(entry.railPerformedFx()));
        appendField(canonical, "prevHash", entry.prevHash());

        // The postings in the order they are held. Order is not normalised: the chain attests to the
        // bytes actually stored, and re-sorting here would mean a stored order the hash does not cover,
        // so a reordering a reader would see would be undetectable.
        appendField(canonical, "postingCount", Integer.toString(entry.postings().size()));
        for (int index = 0; index < entry.postings().size(); index++) {
            appendPosting(canonical, index, entry.postings().get(index));
        }

        return canonical.toString();
    }

    private static void appendPosting(StringBuilder canonical, int index, Posting posting) {
        StringBuilder line = new StringBuilder("posting[").append(index).append("] ");
        line.append("accountId=").append(posting.accountId()).append(' ');
        line.append("direction=").append(posting.direction().name()).append(' ');
        line.append("amountMinor=").append(posting.amount().amountMinor()).append(' ');
        line.append("currencyCode=");
        appendLengthPrefixed(line, posting.currency().code());
        line.append(' ');
        line.append("currencyExponent=").append(posting.currency().minorUnitExponent());
        canonical.append(line).append('\n');
    }

    /** Writes {@code name=<len>:<value>} for a free-form value, or {@code name=-} for null. */
    private static void appendField(StringBuilder canonical, String name, String value) {
        canonical.append(name).append('=');
        appendLengthPrefixed(canonical, value);
        canonical.append('\n');
    }

    private static void appendField(StringBuilder canonical, String name, UUID value) {
        appendField(canonical, name, value == null ? null : value.toString());
    }

    /**
     * Writes {@code <len>:<value>}, or a bare {@code -} for null.
     *
     * <p>The length prefix is what makes the encoding injective, and it is the only reason this is a
     * method rather than concatenation at each call site.
     */
    private static void appendLengthPrefixed(StringBuilder into, String value) {
        if (value == null) {
            into.append(NULL);
            return;
        }
        into.append(value.length()).append(':').append(value);
    }

    private static String digestOf(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            // SHA-256 is required of every Java platform, so this cannot happen. Thrown rather than
            // returning something unhashed, because a chain that verifies without being hashed is worse
            // than a job that fails.
            throw new IllegalStateException(ALGORITHM + " is unavailable on this platform", unavailable);
        }
    }
}
