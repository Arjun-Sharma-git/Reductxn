package io.reductxn.api.port.in;

import java.util.Optional;

/**
 * What holding an invitation code entitles somebody to (ADR 0055).
 *
 * <p><strong>Two values because one instrument serves two audiences, and the word is what keeps them apart.</strong>
 * {@link #MEMBERSHIP} is an invitation: spending it writes a member, which is what every code was before this enum
 * existed. {@link #READ} is a share link: holding it reads the group's positions and joins nobody, which is what a group
 * gives an accountant, a landlord or a mediator - people who need a number and must not become members.
 *
 * <p><strong>It is an enum rather than a boolean, and the difference is what a third value would cost.</strong> An
 * {@code isShare} flag would make "share" the marked case of an unmarked default and could not hold the next thing a
 * code might confer - a code that lets somebody answer one run, say, which is the same instrument again with a third
 * word. The table's CHECK constraint names the two values it will store, so a third arrives as a migration and a
 * decision rather than as a spelling.
 *
 * <p>{@link #wireName()} and {@link #fromWire(String)} are the contract's vocabulary, and they are here for the reason
 * {@link ObligationStatus} gives: the boundary parses one value (a query parameter, one day) and the response mappers
 * write the other, so a constant added to this enum fails to compile until somebody decides what it is called on the
 * wire.
 */
public enum InvitationGrant {

    /** Spending the code joins somebody to the group. The default this build had for every code until ADR 0055. */
    MEMBERSHIP,

    /** Holding the code shows somebody what the group owes whom. Nothing is spent, and nobody joins. */
    READ;

    /** The contract's spelling of this value. */
    public String wireName() {
        return switch (this) {
            case MEMBERSHIP -> "membership";
            case READ -> "read";
        };
    }

    /** The value a wire word names, or empty when it names none - the contract's enum is the list. */
    public static Optional<InvitationGrant> fromWire(String word) {
        for (InvitationGrant grant : values()) {
            if (grant.wireName().equals(word)) {
                return Optional.of(grant);
            }
        }

        return Optional.empty();
    }

    /** Every value, as the contract spells them, for a refusal that has to name the vocabulary it accepts. */
    public static String vocabulary() {
        StringBuilder words = new StringBuilder();

        for (InvitationGrant grant : values()) {
            words.append(words.isEmpty() ? "" : " or ").append(grant.wireName());
        }

        return words.toString();
    }
}
