package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code SettlementRun} schema, for {@code GET /v1/runs/{runId}}.
 *
 * <p><strong>Three of the contract's fields are absent, each for a stated reason rather than for brevity.</strong>
 * {@code reportingTotals} needs the group's reporting currency and a rate; the group is a row since ADR 0043 but
 * this projection does not read it, and a total without a rate as-of is a figure nobody could restate (ADR 0021);
 * {@code fxRequirements} is the worker's derivation of what the group must obtain from outside, and re-deriving it
 * here is the drift ADR 0021 refuses - the FX legs are visible in the transfers instead, on the endpoint that exists
 * for exactly that; and {@code executedAt} has no source, because nothing records the instant a run reached
 * {@code done} (the run's {@code drivenAt} is when the driver last looked, which is a different fact).
 *
 * <p>{@code answers} is an addition to the schema, and the reason it is needed is that the approval endpoint cannot
 * answer the question a member actually has. Its {@code 200} says "recorded", which is true and incomplete: whether
 * an answer reached the run's quorum is a judgement the worker makes, and this is where a member sees the answers as
 * rows. It is not {@code counted} - see {@code io.reductxn.api.port.in.RunReport.Answer} for why that word is
 * missing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettlementRunResponse(
        UUID id,
        UUID cycleId,
        UUID groupId,
        String status,
        List<Partition> partitions,
        List<Answer> answers,
        Instant computedAt,
        Gates gates) {

    /**
     * What the run's gates concluded, as the worker wrote it down (ADR 0023).
     *
     * <p>Projected rather than computed: {@code counted} against {@code required} says how far off a quorum a group
     * is, and {@code outstanding} says which currency the group still has to bring in - both of them the worker's
     * arithmetic, read from {@code settlement_run_gates} and reported as it stands. Absent (not null, because this
     * response serialises without nulls) until the worker has looked at the run, which a client can tell apart from
     * a verdict that says everything is satisfied.
     *
     * <p>{@code status} is the run's status when the gates were read, so a client can compare it with the run's own
     * {@code status} and see whether it is reading the present or a verdict that outlived it.
     */
    public record Gates(Instant concludedAt, String status, Quorum quorum, Funding funding) {

        /** Whether the run has quorum, and the counts that explain the answer (see {@code RunReport.Gates}). */
        public record Quorum(boolean met, int counted, int required, int debtorsStillToConfirm) {
        }

        /** Whether the group has brought the currency in, and what is still missing, per currency. */
        public record Funding(boolean satisfied, List<MoneyDto> outstanding) {
        }
    }

    /** One currency's figures, as {@code settlement_partitions} holds them. */
    public record Partition(
            String currency,
            MoneyDto gross,
            MoneyDto net,
            MoneyDto tax,
            MoneyDto taxSaved,
            int transferCount) {
    }

    /** A member's answer, as the run's decision rows hold it. */
    public record Answer(UUID memberId, String decision, Instant decidedAt) {
    }
}
