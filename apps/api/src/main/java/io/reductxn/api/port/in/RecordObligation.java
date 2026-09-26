package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: record a member's commitment, and book what it does to the ledger.
 *
 * <p>Returns a {@link Result} rather than throwing, because the failures this use case can produce are
 * expected ones: an unknown currency, a member owing themselves, an amount that cannot be posted. The
 * kernel's rule is that expected failures are values, and this is the first place in the module where that
 * rule has a subject - {@code DomainErrorResolver}'s documentation notes that the path for a {@code Result}
 * mapped to a status had no cases yet.
 *
 * <p><strong>Returns rather than throws, and therefore also catches.</strong> {@code libs/fx} declares its
 * failures as exceptions that implement {@code DomainError}, which the resolver maps to a {@code 500} as a
 * programming fault. For a rate that could not be obtained that is the wrong reading - it is a port's
 * declared failure, not a bug - so the implementation catches it and hands it back as a value. The
 * disagreement is real and is recorded in MASTER-PLAN section 15 rather than papered over by loosening the
 * resolver, which would then treat an actual fault as a client error.
 */
public interface RecordObligation {

    /**
     * Records the commitment and books the entries it produces.
     *
     * <p>Idempotent on the request's key: a second call with the same key in the same group answers with
     * what the first one recorded, and writes nothing.
     *
     * @return the obligation as recorded, or the reason it was refused
     */
    Result<RecordedObligation> record(NewObligation request);
}
