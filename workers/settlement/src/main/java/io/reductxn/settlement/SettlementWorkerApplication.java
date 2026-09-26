package io.reductxn.settlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The settlement worker: one deployable, and the second one in the product.
 *
 * <p>It executes what {@code libs/planning} plans. The plan - partitions, per-currency positions, transfers,
 * the {@code fx_clearing} position and the external requirement - is computed by the library, because both
 * this module and {@code apps/api} need it and they may not depend on each other (RULE B6, RULE B21). What
 * this module owns is the *run*: its lifecycle, its quorum, its idempotency, its rail submissions and its
 * funding state.
 *
 * <p>It has no HTTP surface, and must not grow one. A run is a state machine over tables the database
 * arbitrates; an endpoint here would be a second write path to the same rows, with none of the API's
 * validation, idempotency or error vocabulary.
 *
 * <p>It deliberately does not depend on {@code apps/api} (RULE B6). Anything both need belongs in
 * {@code libs/*}, which is why {@code libs/planning} and {@code libs/booking} exist.
 */
@SpringBootApplication
public class SettlementWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementWorkerApplication.class, args);
    }
}
