package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.PreviewNetting;
import io.reductxn.api.web.dto.NettingPreviewMapper;
import io.reductxn.api.web.dto.NettingPreviewResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/groups/{groupId}/netting/preview}, per the contract.
 *
 * <p>An inbound adapter, and the only kind of class that may depend on HTTP. It depends on
 * {@link PreviewNetting} and never on an implementation or a repository, which is RULE B12 - "a
 * controller injecting a repository is the most common way this architecture dies".
 *
 * <p><strong>It reads the clock.</strong> The boundary is where "now" is decided, because nothing below
 * it may: the planner is pure (RULE B21) and the use case takes the instant it was given. Reading it here
 * means an {@code asOf} preview and a live one travel the same path, and a test can pin the clock and get
 * a reproducible answer.
 *
 * <p>It also does no mapping itself. The response is built by {@link NettingPreviewMapper}, so the shape
 * of the contract lives in one file rather than being spread across handlers.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/netting")
public class NettingController {

    private final PreviewNetting previewNetting;
    private final Clock clock;

    public NettingController(PreviewNetting previewNetting, Clock clock) {
        this.previewNetting = Objects.requireNonNull(previewNetting, "previewNetting");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param asOf optional; the contract's parameter for previewing a past moment. Defaults to now.
     */
    @GetMapping("/preview")
    public NettingPreviewResponse preview(
            @PathVariable UUID groupId,
            @RequestParam(name = "asOf", required = false) Instant asOf) {

        return NettingPreviewMapper.toResponse(
                previewNetting.preview(groupId, asOf == null ? clock.instant() : asOf));
    }
}
