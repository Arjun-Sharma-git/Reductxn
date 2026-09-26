package io.reductxn.api.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The user accounts. Owned by {@code apps/api} - the tables are this module's own (MASTER-PLAN section 5.5).
 *
 * <p><strong>One method for finding and creating, because the two are one operation here.</strong> A sign-in is a
 * registration in an OTP build: the code proves the number, so a number nobody has seen becomes an account. Asking
 * {@code findByPhone} and then {@code create} would be the check-then-insert that loses the race - two verifications of
 * one number at the same instant would both find nothing and both insert - and {@code users_phone_numbers_are_unique} is
 * what settles it. The store therefore inserts with {@code ON CONFLICT DO NOTHING} and reads back the row either way, and
 * answers with the account, exactly as the members write does with its primary key.
 *
 * <p>There is no {@code update} and no {@code delete}: renaming a user is a decision this build has not made, and an
 * operation nobody asked for is a way to change a fact without anybody deciding it should be changeable.
 */
public interface UserAccounts {

    /**
     * The account for this number, created if this is the first time anybody has proved it.
     *
     * @return the account as stored, so the caller has the id a session needs
     */
    Account findOrCreate(String phoneNumber, String displayName, Instant createdAt);

    /** The account by id, for the reads that hold a session rather than a number. */
    Optional<Account> findById(UUID id);

    /**
     * A user account: the id other boundaries name, the number that proves it, and what this deployment calls them.
     *
     * @param id          the user id
     * @param phoneNumber the number that identifies them
     * @param displayName what the product calls them, derived from the number at creation
     */
    record Account(UUID id, String phoneNumber, String displayName) {
    }
}
