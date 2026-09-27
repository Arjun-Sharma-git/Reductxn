package io.reductxn.api.application.rails;

import io.reductxn.api.port.in.AcceptRailCallback;
import io.reductxn.api.port.in.NewRailCallback;
import io.reductxn.api.port.in.RailCallbackRefusals;
import io.reductxn.api.port.in.RecordedRailCallback;
import io.reductxn.api.port.out.RailCallbackInbox;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

/**
 * The use case: record a rail's callback, having established it is the rail's.
 *
 * <p><strong>The one judgement here is the vocabulary.</strong> A callback is the rail's own word, and the four words
 * this build can act on are the contract's enum, {@code V513}'s CHECK, and this set. A fifth word is refused with a
 * {@code 400} naming it rather than stored, because the driver maps a stored word to a transfer's state exhaustively -
 * a row it could not map would be an answer that reached the rows as a question.
 *
 * <p><strong>So the vocabulary exists three times, and each copy has a job.</strong> The CHECK is the last line: it
 * refuses a row no writer should have produced. The contract's enum is the promise to the rail - it is what a client
 * generates types from. This set is the one that produces a readable answer instead of a failed insert, which is what a
 * rail integrating with this deployment needs. A single copy is not available: two of the three are not Java, and the
 * worker's adapter adds an exhaustive switch that turns a stored word into a state. The alternative - accepting any
 * word and hoping - is the one option that is worse than the duplication.
 *
 * <p><strong>Nothing is checked about the reference.</strong> Whether a transfer was ever offered under it is the
 * worker's fact, in the worker's table; a row for a reference nobody asks about is inert, and the worker's test says
 * so. Checking here would mean this module reading another module's rows to write a row of its own.
 */
@Service
public class RecordRailCallbackService implements AcceptRailCallback {

    /** The four words a callback's state may be, as the contract declares them and {@code V513} enforces them. */
    private static final Set<String> KNOWN_STATES = Set.of("accepted", "pending", "confirmed", "failed");

    private final RailCallbackInbox callbacks;

    public RecordRailCallbackService(RailCallbackInbox callbacks) {
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
    }

    @Override
    public Result<RecordedRailCallback> accept(NewRailCallback callback) {
        Objects.requireNonNull(callback, "callback");

        // A reference is the identity of the row and of the transfer it is about, so an empty one is a callback that
        // cannot be filed - and it is checked as *not understood* rather than as absent, because the rail did send the
        // field, with nothing in it.
        if (callback.railRef().isBlank()) {
            return Result.fail(new RailCallbackRefusals.NotUnderstood("railRef", callback.railRef()));
        }

        if (!KNOWN_STATES.contains(callback.state())) {
            return Result.fail(new RailCallbackRefusals.NotUnderstood("state", callback.state()));
        }

        callbacks.record(callback);

        return Result.ok(new RecordedRailCallback(
                callback.provider(), callback.railRef(), callback.state(), callback.receivedAt()));
    }
}
