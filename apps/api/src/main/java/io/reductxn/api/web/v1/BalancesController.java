package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadGroupBalances;
import io.reductxn.api.web.dto.NetPositionResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/groups/{groupId}/balances}, per the contract.
 *
 * <p>An inbound adapter, and it depends on {@link ReadGroupBalances} rather than on a repository or an
 * implementation, which is RULE B12.
 *
 * <p><strong>It reads the clock, and it is the only place that does.</strong> The contract gives this operation no
 * {@code asOf}: a balance is the answer to "what do we owe each other now", and a caller who wants a past instant is
 * asking for the what-if the preview's {@code asOf} parameter already answers. Reading "now" here rather than in the
 * use case is the same choice the preview's controller made, for the same reason - so a test can pin the clock and
 * get a reproducible answer.
 *
 * <p>The response is a list of the contract's {@code NetPosition} schema, built by
 * {@link NetPositionResponse#of}, which is also what the preview's plan uses. One schema, one Java type, whichever
 * endpoint is answering.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/balances")
public class BalancesController {

    private final ReadGroupBalances balances;
    private final Clock clock;

    public BalancesController(ReadGroupBalances balances, Clock clock) {
        this.balances = Objects.requireNonNull(balances, "balances");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public List<NetPositionResponse> balances(@PathVariable UUID groupId) {
        return balances.balances(groupId, clock.instant()).stream()
                .map(NetPositionResponse::of)
                .toList();
    }
}
