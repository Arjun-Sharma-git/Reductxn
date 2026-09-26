package io.reductxn.api.port.in;

/**
 * Where an obligation has got to, matching the contract's {@code status} enum.
 *
 * <p>Four values because the contract has four. Until ADR 0036 only two were producible - an obligation was
 * {@link #OPEN} the moment it was recorded and {@link #VOIDED} once {@code voided_at} was set - and this comment
 * said so, adding that {@link #NETTED} and {@link #SETTLED} needed a settlement run and "not producible until the
 * worker exists". The worker exists (ADR 0032), so the sentence was stale and the read path was wrong: an obligation
 * a settled run had discharged was reported {@code open}. All four are produced now, by the derivation in
 * {@code ObligationStatuses}, which is the one place that decides them.
 *
 * <p>{@link #wireName()} and {@link #fromWire(String)} are the vocabulary the contract declares, and they are here
 * rather than in a mapper because four readers need them (the list, the response mapping, the replay path and the
 * boundary that parses a query parameter). Each is an exhaustive switch, so a constant added to this enum fails to
 * compile until somebody decides what it is called on the wire - which is better than a {@code name()} that quietly
 * ships a Java identifier as a protocol value.
 */
public enum ObligationStatus {

    /** Recorded and still owed: the state every newly recorded obligation is in, and the state a released one returns to. */
    OPEN,

    /** A run is holding it. Settles or releases when that run ends. */
    NETTED,

    /** A run that ended in `done` discharged it: the money has moved. */
    SETTLED,

    /** Voided rather than deleted (ADR 0004). */
    VOIDED;

    /** The word the contract and the database use. */
    public String wireName() {
        return switch (this) {
            case OPEN -> "open";
            case NETTED -> "netted";
            case SETTLED -> "settled";
            case VOIDED -> "voided";
        };
    }

    /** The status a wire value names, or empty when it names none of them. */
    public static java.util.Optional<ObligationStatus> fromWire(String word) {
        for (ObligationStatus status : values()) {
            if (status.wireName().equals(word)) {
                return java.util.Optional.of(status);
            }
        }

        return java.util.Optional.empty();
    }

    /** Every word this build accepts, for a refusal that tells a caller what it does accept. */
    public static String vocabulary() {
        return "open, netted, settled or voided";
    }
}
