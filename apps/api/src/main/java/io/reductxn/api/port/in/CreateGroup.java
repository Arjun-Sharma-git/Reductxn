package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: create a group.
 *
 * <p><strong>Creating a group makes its creator a member</strong>, with the {@code admin} role and the name the deployment
 * knows them by. It is not a flourish: {@code GET /groups} answers with the groups somebody belongs to, so a group whose
 * creator was not a member would be invisible to the only person who could have asked for it, and the client would look
 * broken while the server was technically right. That is also why the two writes are one transaction - a group that exists
 * with no member is a state nothing should be able to observe (RULE B15).
 *
 * <p><strong>Idempotent on the creator and the key.</strong> The contract requires an {@code Idempotency-Key} and there is
 * no group in the path to scope it by, so the pair is the scope: a retry answers with the group the first attempt created
 * and writes nothing, which is what makes a client's retry safe rather than a way to end up with two households (P8).
 */
public interface CreateGroup {

    /** @return the group as stored and whether this call created it, or the reason it was refused */
    Result<CreatedGroup> create(NewGroup request);
}
