package io.reductxn.ledger.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Cross-chain verification of FX linkage (invariant L9).
 *
 * <p>A cross-currency obligation becomes two entries in two currencies, joined by an {@code fx_link_id}
 * (ADR 0012 point 3). Each is internally balanced, so a hash chain verifies within each currency - and
 * that proves nothing at all about the other side. An entry can be perfectly valid, chained, balanced and
 * alone. The linkage therefore has to be verified <em>across</em> chains, which is what this does and why
 * L9 exists as a separate invariant from L4.
 *
 * <p>A link is paired when it has exactly one {@link FxRole#BASE} and one {@link FxRole#QUOTE} entry, in
 * one group. Group is part of the pairing key rather than a separate check: the same link id reused in
 * another group is two halves of nothing, and reporting it is the honest answer.
 *
 * <p><strong>Both directions are checked, where L9's wording checks one.</strong> L9 says every base
 * entry has exactly one quote partner. Checking only that would accept an orphan quote - a deletion that
 * is detectable precisely because the pair is asymmetric. A one-way check would leave a way to remove
 * value from the ledger by removing the entry that recorded it.
 *
 * <p><strong>What this does not prove.</strong> Spec section 3 asks for a second pass over the same
 * links: that the pair matches the stored quote. That needs the quote itself, which lives in
 * {@code libs/fx} and is deliberately invisible to this module (RULE B19) - a class that cannot see a
 * rate cannot check one. Structural pairing is this module's half; value matching belongs with the quote.
 *
 * <p>Problems are returned rather than thrown, for the same reason as {@link EntryChain}: a broken link
 * is the finding a verifier job reports, and one finding should not hide the rest.
 */
public final class FxLinkage {

    /** Exactly the two sides of a conversion. An implicit size check would depend on the enum's arity. */
    private static final Set<FxRole> BOTH_ROLES = EnumSet.of(FxRole.BASE, FxRole.QUOTE);

    private FxLinkage() {
    }

    /**
     * The link ids that are not exactly one base and one quote entry in a single group.
     *
     * <p>Sorted, so a job's output and a test's expectation agree without either sorting it. Only entries
     * that are actually linked participate: an unlinked entry is not an unpaired one.
     *
     * @return an empty set when every link is paired
     */
    public static Set<UUID> unpairedLinkIds(Collection<JournalEntry> entries) {
        Map<LinkKey, List<JournalEntry>> byLink = new HashMap<>();

        for (JournalEntry entry : entries) {
            if (entry.isFxLinked()) {
                byLink.computeIfAbsent(
                                new LinkKey(entry.groupId(), entry.fxLinkId()),
                                key -> new ArrayList<>())
                        .add(entry);
            }
        }

        Set<UUID> unpaired = new TreeSet<>();
        for (Map.Entry<LinkKey, List<JournalEntry>> link : byLink.entrySet()) {
            if (!isPaired(link.getValue())) {
                unpaired.add(link.getKey().linkId());
            }
        }
        return unpaired;
    }

    private static boolean isPaired(List<JournalEntry> sides) {
        if (sides.size() != 2) {
            return false;
        }

        Set<FxRole> roles = EnumSet.noneOf(FxRole.class);
        for (JournalEntry side : sides) {
            roles.add(side.fxRole());
        }
        return roles.equals(BOTH_ROLES);
    }

    /**
     * A link is identified by its id <em>within a group</em>. Keying on the id alone would pair a base
     * entry in one group with a quote entry in another, and L9 says the partner is in the same group.
     */
    private record LinkKey(UUID groupId, UUID linkId) {
    }
}
