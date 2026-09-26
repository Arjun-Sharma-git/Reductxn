package io.reductxn.api.adapter.persistence;

import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.Invitation;
import io.reductxn.api.port.out.Invitations;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code invitations}, as jOOQ: this module's own table (MASTER-PLAN section 5.5).
 *
 * <p><strong>The code is generated here, and that is the one place it can be.</strong> A code is the primary key, so "is
 * this code free" is a question only the database answers - and the answer arrives as the insert's own effect rather
 * than as a second query. {@link #record} therefore tries a code and retries one that collided, which is the
 * check-then-insert race avoided rather than lost: two invitations created in the same millisecond cannot share a code,
 * because one of them finds the key taken and picks another.
 *
 * <p>The alphabet is the table's CHECK constraint, symbol for symbol: eight characters from thirty-two (forty bits),
 * with `0`, `1`, `I` and `O` left out because a code is transcribed by a person. {@link #MAX_ATTEMPTS} bounds the
 * retries, and the bound is a statement about arithmetic rather than about load: a collision is a one-in-a-trillion
 * event per attempt, so running out of attempts means something other than bad luck - a table full of identical codes,
 * or an alphabet that does not match the constraint - and it is refused loudly instead of looped on.
 *
 * <p><strong>{@link #spend} carries its condition into the statement.</strong> "Unexpired, and within its limit" is
 * evaluated by the database against the row as it stands at that moment, which is what makes two acceptances racing for
 * one last use resolve to one winner rather than to two readers who both thought they saw a free use. The method's
 * answer is the effect: true when this call took one.
 */
@Repository
public class JooqInvitations implements Invitations {

    private static final Table<Record> INVITATIONS = DSL.table("invitations");

    private static final Field<String> CODE = DSL.field("code", String.class);
    private static final Field<UUID> GROUP_ID = DSL.field("group_id", UUID.class);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", OffsetDateTime.class);
    private static final Field<OffsetDateTime> EXPIRES_AT = DSL.field("expires_at", OffsetDateTime.class);
    private static final Field<Integer> MAX_USES = DSL.field("max_uses", Integer.class);
    private static final Field<Integer> USES = DSL.field("uses", Integer.class);

    /**
     * The thirty-two symbols a code may use, which is `V105`'s CHECK constraint written out.
     *
     * <p>The order is the constraint's own ranges - `A-H`, `J-N`, `P-Z`, then `2-9` - so the two can be read against
     * each other; nothing depends on it, because a code is random rather than sequential.
     */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** Eight characters of a thirty-two-symbol alphabet: forty bits. */
    private static final int CODE_LENGTH = 8;

    /** How many times a collision is retried before the table itself is treated as the problem. */
    private static final int MAX_ATTEMPTS = 5;

    private final DSLContext dsl;
    private final SecureRandom random = new SecureRandom();

    public JooqInvitations(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public CreatedInvite record(UUID groupId, Instant createdAt, Instant expiresAt, Integer maxUses) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = code();

            int inserted = dsl.insertInto(INVITATIONS)
                    .columns(CODE, GROUP_ID, CREATED_AT, EXPIRES_AT, MAX_USES)
                    .values(code, groupId, at(createdAt), at(expiresAt), maxUses)
                    .onConflict(CODE)
                    .doNothing()
                    .execute();

            if (inserted > 0) {
                return new CreatedInvite(code, expiresAt, maxUses);
            }
        }

        throw new IllegalStateException(
                "could not find a free invitation code in " + MAX_ATTEMPTS + " attempts. One collision is bad luck; "
                        + MAX_ATTEMPTS + " in a row means the table or the alphabet is the problem, and it is not "
                        + "something to keep trying");
    }

    @Override
    public Optional<Invitation> findByCode(String code) {
        Record row = dsl.select(GROUP_ID, EXPIRES_AT, MAX_USES, USES)
                .from(INVITATIONS)
                .where(CODE.eq(canonical(code)))
                .fetchOne();

        if (row == null) {
            return Optional.empty();
        }

        return Optional.of(new Invitation(
                row.get(GROUP_ID),
                row.get(EXPIRES_AT).toInstant(),
                row.get(MAX_USES),
                row.get(USES)));
    }

    @Override
    public boolean spend(String code, Instant now) {
        // The condition is the statement rather than a check before it: `uses < max_uses` has to hold at the moment the
        // increment happens, or two acceptances of the last use both pass a check and both spend it.
        int spent = dsl.update(INVITATIONS)
                .set(USES, USES.plus(1))
                .where(CODE.eq(canonical(code)))
                .and(EXPIRES_AT.gt(at(now)))
                .and(MAX_USES.isNull().or(USES.lt(MAX_USES)))
                .execute();

        return spent > 0;
    }

    /** A candidate code, from the alphabet the table's CHECK constraint names. */
    private String code() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);

        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }

        return code.toString();
    }

    /**
     * The code as this table's alphabet spells it, so a code a person typed in lowercase is the same code.
     *
     * <p><strong>Arithmetic rather than a locale, and the alphabet is what makes that correct.</strong> Every symbol is
     * ASCII, so the difference between the two spellings of a letter is exactly 32 - no {@code Locale}, no
     * {@code toUpperCase}, and nothing that could depend on where the server happens to be running (RULE B20: a server
     * that cannot see a locale cannot render one). Anything outside the alphabet is left alone, which is deliberate: a
     * code with a `0`, `1`, `I` or `O` in it is a code this table does not hold, and it deserves the `404` it will get
     * rather than a spelling correction.
     */
    private static String canonical(String code) {
        StringBuilder canonical = new StringBuilder(code.length());

        for (int index = 0; index < code.length(); index++) {
            char symbol = code.charAt(index);

            canonical.append(symbol >= 'a' && symbol <= 'z' ? (char) (symbol - 32) : symbol);
        }

        return canonical.toString();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
