package io.reductxn.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The settlement worker: one deployable, and the second one in the product.
 *
 * <p>It executes what {@code libs/planning} plans. The plan - partitions, per-currency positions, transfers,
 * the {@code fx_clearing} position and the external requirement - is computed by the library, because both
 * this module and {@code apps/api} need it and they may not depend on each other (RULE B6, RULE B21). What
 * this module owns is the *run*: its lifecycle, its quorum, its idempotency, its rail submissions and its
 * funding state. And, since {@code V505}, the fact that a run has to be *driven*: nothing else in the product
 * moves a run from waiting to executing, so a cadence here does it (ADR 0018).
 *
 * <p>It has no HTTP surface, and must not grow one. A run is a state machine over tables the database
 * arbitrates; an endpoint here would be a second write path to the same rows, with none of the API's
 * validation, idempotency or error vocabulary. Scheduling and a command line are not network surfaces: the
 * first is how it drives itself, and the second is an operator with a shell.
 *
 * <p>It deliberately does not depend on {@code apps/api} (RULE B6). Anything both need belongs in
 * {@code libs/*}, which is why {@code libs/planning} and {@code libs/booking} exist.
 *
 * <p><strong>{@code @EnableScheduling} is what keeps the process alive, not just what triggers it.</strong>
 * This is a non-web application: before the driver existed, {@code main} returned, the context had no
 * non-daemon thread, and the JVM exited - so the worker "running as a service" did nothing at all between
 * deployments that only ever ran its tests. A thread that wakes on a cadence is a thread that stays.
 */
@SpringBootApplication
@EnableScheduling
public class SettlementWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementWorkerApplication.class, args);
    }
}
