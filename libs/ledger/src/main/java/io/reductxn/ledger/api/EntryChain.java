package io.reductxn.ledger.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Verification of a hash chain, run offline over stored entries (invariant L4, spec section 3).
 *
 * <p>Entries are chained per {@code (group_id, currency)}, so a chain is a homogeneous sequence: every
 * entry in it shares one group and one currency. Verification is a pure function over that sequence,
 * which is what lets an offline job verify the ledger from the rows alone - independently auditable
 * without the application, a database driver or a rail.
 *
 * <p><strong>Problems are returned, not thrown.</strong> A broken chain is not a programming fault; it
 * is the finding the job exists to report. Throwing would let the first problem hide every later one,
 * and an auditor wants the whole list: a tampered entry is one finding, and an entry inserted without
 * being recorded is a second in the same chain.
 *
 * <p><strong>What a valid chain does not prove.</strong> It can still be a forgery by someone able to
 * rewrite every entry after the tampered one. The chain detects anything short of that, which is why the
 * tables are also append-only at the database level (ADR 0004): the two defences cover different attacks
 * and neither covers the other's.
 */
public final class EntryChain {

    private EntryChain() {
    }

    /**
     * Everything wrong with a chain, in the order the problems would be read.
     *
     * <p>The caller supplies the entries in chain order, oldest first. Order is the caller's knowledge,
     * not the model's - an entry knows its predecessor's hash, not its own position.
     *
     * @return an empty list when the chain verifies; one message per problem otherwise
     */
    public static List<String> problemsIn(List<JournalEntry> chain) {
        List<String> problems = new ArrayList<>();
        if (chain.isEmpty()) {
            return problems;
        }

        JournalEntry genesis = chain.get(0);
        if (genesis.prevHash() != null) {
            problems.add("entry " + genesis.id() + " was supplied as the start of the chain but "
                    + "references a previous hash (" + genesis.prevHash() + "), so entries before it are "
                    + "missing from this chain");
        }

        problems.addAll(inhomogeneousEntries(chain, genesis));
        problems.addAll(brokenLinks(chain));

        return problems;
    }

    /**
     * The hash a new entry in this chain must reference as its {@code prevHash}, or null for the first.
     *
     * <p>Exists so the genesis case is decided once, here, rather than in every writer. A writer that
     * assumed a non-empty chain would fail on a group's first entry, and one that assumed null would
     * never join the chain at all.
     */
    public static String headHash(List<JournalEntry> chain) {
        return chain.isEmpty() ? null : chain.get(chain.size() - 1).hash();
    }

    /**
     * A chain is per group and currency, so an entry that belongs to a different one is not merely
     * misordered - it is in the wrong chain, and its link can never verify against the right predecessor.
     */
    private static List<String> inhomogeneousEntries(List<JournalEntry> chain, JournalEntry genesis) {
        List<String> problems = new ArrayList<>();

        for (int index = 1; index < chain.size(); index++) {
            JournalEntry entry = chain.get(index);
            if (!entry.groupId().equals(genesis.groupId())) {
                problems.add("entry " + entry.id() + " is in group " + entry.groupId()
                        + " but the chain starts in group " + genesis.groupId()
                        + "; chains are per group and currency");
            } else if (!entry.currency().equals(genesis.currency())) {
                problems.add("entry " + entry.id() + " is in " + entry.currency().code()
                        + " but the chain starts in " + genesis.currency().code()
                        + "; a conversion is two chains joined by fx_link_id, not one chain");
            }
        }

        return problems;
    }

    private static List<String> brokenLinks(List<JournalEntry> chain) {
        List<String> problems = new ArrayList<>();

        for (int index = 1; index < chain.size(); index++) {
            JournalEntry previous = chain.get(index - 1);
            JournalEntry current = chain.get(index);

            String expected = previous.hash();
            if (!expected.equals(current.prevHash())) {
                problems.add("entry " + current.id() + " references " + current.prevHash()
                        + " but the entry before it hashes to " + expected
                        + ", so one of the two has been altered or an entry between them is missing");
            }
        }

        return problems;
    }
}
