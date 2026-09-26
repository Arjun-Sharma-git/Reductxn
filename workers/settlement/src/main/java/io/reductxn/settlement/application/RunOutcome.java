package io.reductxn.settlement.application;

import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.TransferStatus;

import java.util.Objects;

/**
 * What a run's status is, given the states of its transfers.
 *
 * <p><strong>A run with nothing to move is done</strong>, and that is not a special case bolted on: a cycle
 * whose obligations cancel out produces no transfers, and refusing to finish it would leave a group unable to
 * close a cycle in which nothing was owed. {@code allMatch} on an empty stream is true, so this falls out of
 * the arithmetic rather than being written twice.
 *
 * <p>A terminal failure makes the run {@code failed} even if other transfers are still in flight, because the
 * run's report has to say that the settlement did not happen (S3). The in-flight ones are still polled by a
 * later pass, and the run stays failed when they resolve.
 *
 * <p><strong>It is a class rather than a private method because there are two writers of a run's fate now.</strong>
 * Execution decides it after a pass at the rail (ADR 0017), and so does a person's resolution of a silent transfer:
 * an answer that the money did not move fails the run, and answers that confirm everything complete it (ADR 0025).
 * A second copy of these five lines in the resolve use case would be a second answer to one question, and the
 * failure mode is the one this project keeps writing down - the two would agree until the day a state is added and
 * only one of them is taught about it. It lives in {@code application} because both callers are use cases and no
 * adapter needs it: `Backoff`'s placement rather than `Escalation`'s.
 *
 * <p>Nothing here reads a clock, a database or a rail - it is a function of the rows a caller has already read, so
 * a run's fate can be asserted in a test with three transfers and no container.
 */
public final class RunOutcome {

    private RunOutcome() {
    }

    /**
     * The status a run whose transfers are in these states has.
     *
     * <p>Read from the run's transfers rather than from what a use case believes it just did, which is what makes
     * the two writers agree: both call this with the run as it was read back from the rows.
     *
     * @param run the run as it stands, with its transfers
     * @return {@code done} when every transfer is confirmed, {@code failed} when any is, else {@code executing}
     */
    public static RunStatus of(ComputedRun run) {
        Objects.requireNonNull(run, "run");

        if (run.transfers().stream().allMatch(transfer -> transfer.status() == TransferStatus.CONFIRMED)) {
            return RunStatus.DONE;
        }

        if (run.transfers().stream().anyMatch(transfer -> transfer.status() == TransferStatus.FAILED)) {
            return RunStatus.FAILED;
        }

        return RunStatus.EXECUTING;
    }
}
