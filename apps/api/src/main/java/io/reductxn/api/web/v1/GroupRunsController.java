package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadGroupRuns;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.RunMapper;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/groups/{groupId}/runs}, per the contract: the runs a group has to decide about, newest first.
 *
 * <p><strong>This is the endpoint the approval path was missing.</strong> {@code POST /runs/{runId}/decisions} and
 * {@code GET /runs/{runId}} existed, and a member could only reach them with an identifier they had no way to
 * obtain - the runs a worker had computed were addressable but not findable. A page of a group's runs is what turns
 * "answer this run" into something a person can do.
 *
 * <p><strong>Responses are the full run, not a summary shape.</strong> The rows go through the same
 * {@link RunMapper} the read endpoint uses, so a list row carries the figures a member needs to decide whether to
 * open it - and, more importantly, so the two endpoints cannot describe one run differently: a second DTO for list
 * rows is the second implementation of "what a run looks like" that ADR 0021 spends its length refusing. The
 * contract's `SettlementRun` schema already says `transfers` are served elsewhere, and a list of twenty runs does not
 * need every rail instruction in it.
 *
 * <p><strong>The group id is parsed here rather than bound as a {@code UUID}.</strong> The same compromise
 * {@code RunsController} makes for a run id: binding it would answer a malformed id with Spring's own {@code 400},
 * whose body carries no code, and a client that cannot branch on a code has to parse prose. (The netting preview
 * takes the shorter route and binds the UUID directly; the difference is real and one of the two should move.)
 *
 * <p>No {@code 404} for a group nobody has heard of: groups are not tables in this build, so "no such group" and
 * "a group with no runs" cannot be told apart here, and this answers the empty list that is true of both.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/runs")
public class GroupRunsController {

    /** The contract's default for a list endpoint, and the same one the activity feed uses. */
    private static final int DEFAULT_LIMIT = 20;

    private final ReadGroupRuns readGroupRuns;
    private final CallerResolver callers;

    public GroupRunsController(ReadGroupRuns readGroupRuns, CallerResolver callers) {
        this.readGroupRuns = Objects.requireNonNull(readGroupRuns, "readGroupRuns");
        this.callers = Objects.requireNonNull(callers, "callers");
    }

    @GetMapping
    public ResponseEntity<Object> list(
            @PathVariable String groupId,
            @RequestParam(name = "limit", required = false) Integer limit,
            HttpServletRequest http) {

        // Who is asking first, since ADR 0054: a group's runs are a page of its history, so a request with no session
        // is refused as a 401 before the id is judged - the order the group writes have used since ADR 0043.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<List<RunReport>> runs = readGroupRuns.list(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                UUID.fromString(groupId),
                limit == null ? DEFAULT_LIMIT : limit);

        if (runs instanceof Result.Failure<List<RunReport>> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(((Result.Ok<List<RunReport>>) runs).value()
                .stream()
                .map(RunMapper::toResponse)
                .toList());
    }

    private static DomainError malformedGroupId(String groupId) {
        if (groupId == null || groupId.isBlank() || !isUuid(groupId)) {
            return new RunRefusals.RequestIncomplete("groupId in the path, as a uuid");
        }

        return null;
    }

    private static boolean isUuid(String candidate) {
        try {
            UUID.fromString(candidate);

            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
