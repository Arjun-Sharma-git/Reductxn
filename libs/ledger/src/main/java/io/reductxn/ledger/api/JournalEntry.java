package io.reductxn.ledger.api;

import io.reductxn.ledger.internal.EntryHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, balanced, single-currency movement of value: the ledger's unit of record.
 *
 * <p>The whole type is a guard. It cannot be constructed unbalanced (L1), it cannot mix currencies (L6),
 * it cannot hold a non-positive amount (L2, in {@link Posting}), and it cannot be modified at all - so
 * the invariants are properties of the model rather than rules a caller has to remember.
 *
 * <p><strong>L6 is deliberately intact, and that is what keeps L1 expressible.</strong> A
 * cross-currency obligation becomes two linked single-currency entries rather than one mixed entry
 * (ADR 0012 point 3), which is why L1 is a single sum here instead of a sum per currency. Weakening L6
 * to save a column was considered and rejected in ADR 0012: it would cost the invariant. There is
 * deliberately no constructor that accepts mixed postings, so the weakening is not available to a
 * caller who has not read the ADR.
 *
 * <p><strong>The hash is derived, not stored.</strong> Invariant L4 says
 * {@code hash = H(prev_hash, canonical(entry, postings))}, and {@link #hash()} computes exactly that.
 * A hash held as a field could disagree with the content it is supposed to attest to; a derived one
 * cannot, because the content is the only input. Persistence still stores the value in the {@code hash}
 * column - it is the output of the derivation, and the verifier recomputes it and compares.
 *
 * <p><strong>Append-only is enforced below this type, not by it.</strong> Nothing here can change an
 * existing entry, and a correction is a new entry with the postings reversed (L3). The real guarantee is
 * that the application's database role has no {@code UPDATE} or {@code DELETE} grant on the table
 * (ADR 0004), which is a schema property and tracked with it.
 *
 * @param id              identity of this entry
 * @param groupId         the group whose book this belongs to; chains are per group and currency
 * @param currency        the single currency every posting must be in (L6)
 * @param occurredAt      when the movement happened, supplied by the caller - this module never reads a
 *                        clock, so a stored entry replays identically
 * @param kind            what the entry records
 * @param createdBy       the user who caused it, or null for a system-generated entry
 * @param idempotencyKey  the key of the operation that produced it, or null when there is none; a
 *                        retried operation writes the same key and the second attempt is recognisable
 * @param fxLinkId        joins the two entries of a conversion, or null when not linked
 * @param fxRole          which side of that conversion this is, or null when not linked
 * @param railPerformedFx whether the rail performed the conversion rather than reductxn (ADR 0012)
 * @param postings        two or more postings, summing to zero (L1), all in {@code currency} (L6)
 * @param prevHash        the hash of the preceding entry in this chain, or null at the chain's start
 */
public record JournalEntry(
        UUID id,
        UUID groupId,
        Currency currency,
        Instant occurredAt,
        EntryKind kind,
        UUID createdBy,
        String idempotencyKey,
        UUID fxLinkId,
        FxRole fxRole,
        boolean railPerformedFx,
        List<Posting> postings,
        String prevHash) {

    public JournalEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(postings, "postings");
        postings = List.copyOf(postings);

        if (postings.size() < 2) {
            throw new IllegalArgumentException(
                    "a journal entry needs at least two postings, or there is nothing for one side to "
                            + "balance against, but had " + postings.size());
        }

        // L6 before L1, deliberately. Once one currency is established, L1 is a single sum rather than a
        // sum per currency, which is precisely the expressibility ADR 0012 preserved by refusing to
        // weaken this invariant.
        for (Posting posting : postings) {
            if (!currency.equals(posting.currency())) {
                throw new CurrencyMismatchException(currency, posting.currency());
            }
        }

        Money net = Money.zero(currency);
        for (Posting posting : postings) {
            net = net.plus(posting.signedAmount());
        }
        if (!net.isZero()) {
            throw new IllegalArgumentException(
                    "journal entry does not balance (L1): debits and credits differ by " + net
                            + " in " + currency.code() + ", and value is never created or destroyed by "
                            + "an entry");
        }

        // The linkage fields are a pair, not two independent columns. One without the other is not
        // partially linked - it is unverifiable, because L9 needs the id to find the partner and the
        // role to know which side this entry is.
        if ((fxLinkId == null) != (fxRole == null)) {
            throw new IllegalArgumentException(
                    "fxLinkId and fxRole are either both present or both absent (L9), but fxLinkId was "
                            + fxLinkId + " and fxRole was " + fxRole);
        }

        if (idempotencyKey != null && idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "an idempotencyKey is either absent or meaningful; a blank one would look set while "
                            + "matching every other blank key");
        }
    }

    /**
     * This entry's hash (L4): the SHA-256 digest of its canonical form, including {@link #prevHash()}.
     *
     * <p>Recomputed on each call rather than cached, because the record holds no hash field - which is
     * the point. It is a pure function of the content, so two entries with equal content have equal
     * hashes, and no entry can exist whose hash disagrees with what it contains.
     *
     * <p>Lower-case hex, 64 characters. The encoding is versioned, so a future change to it invalidates
     * existing chains loudly rather than silently.
     */
    public String hash() {
        return EntryHash.of(this);
    }

    /** Whether this entry is one side of a conversion, and so has a partner to be found (L9). */
    public boolean isFxLinked() {
        return fxLinkId != null;
    }

    /**
     * A short form for logs: the identity and shape, not all twelve components and every posting.
     *
     * <p>The hash is included because it, not the id, is what ties an entry to its chain - an auditor
     * either has the canonical encoding or needs to find the entry by this value.
     */
    @Override
    public String toString() {
        return "JournalEntry[" + id + " " + kind + " " + currency.code()
                + " " + postings.size() + " postings hash=" + hash() + "]";
    }
}
