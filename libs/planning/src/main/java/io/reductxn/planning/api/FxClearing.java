package io.reductxn.planning.api;

import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.NetPosition;

import java.util.UUID;

/**
 * The `fx_clearing` account: the internal counterparty that makes a cross-currency settlement expressible
 * as two ordinary single-currency journal entries (ADR 0012 point 3).
 *
 * <p>It is a real account with a real position and `owner_type = 'fx'`, not a slush fund and not a
 * rounding bucket. When its position is non-zero at the end of a cycle the group has a genuine
 * external FX requirement, and reductxn says so rather than declaring the group settled (P2, RULE S5).
 *
 * <p>This module owns the only model of it (RULE B21).
 */
public final class FxClearing {

    /**
     * The reserved account id.
     *
     * <p>A fixed sentinel rather than a configured value, because it must be identical in every
     * partition and every run for two linked entries to be matchable by an auditor. Chosen to be
     * visually distinctive so it is never mistaken for a member id in a log.
     */
    public static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-00000000f0c1");

    private FxClearing() {
    }

    /** Whether the given participant is the clearing account rather than a member. */
    public static boolean isClearing(UUID participantId) {
        return ACCOUNT_ID.equals(participantId);
    }

    /**
     * Whether this position belongs to the clearing account.
     *
     * <p>Used to split a partition's transfers into member transfers and the legs of the group's
     * external requirement.
     */
    public static boolean isClearing(NetPosition position) {
        return isClearing(position.userId());
    }
}
