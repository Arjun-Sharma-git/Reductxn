package io.reductxn.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The reductxn API: one deployable, many internal modules.
 *
 * <p>A modular monolith (ADR 0009), not a set of services. The boundaries inside it are enforced by
 * ArchUnit rather than by deployment distance, which is only credible because the pure domain logic
 * lives in {@code libs/*} and cannot be reached around - this module holds the HTTP boundary, the use
 * cases and the adapters, and nothing else (RULE B12-B16).
 *
 * <p>It deliberately does not depend on {@code workers/settlement}, and may not (RULE B6). Anything both
 * need belongs in {@code libs/*}, which is why {@code libs/planning} exists.
 */
@SpringBootApplication
public class ReductxnApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReductxnApplication.class, args);
    }
}
