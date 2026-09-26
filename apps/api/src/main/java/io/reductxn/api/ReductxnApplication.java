package io.reductxn.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

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
 *
 * <p><strong>{@code @EnableScheduling} arrived with rate ingestion (ADR 0027).</strong> This module had no
 * scheduled work until then: a request did everything it did. Reading a provider's published rates is not a
 * request - it happens whether or not anybody is asking, it must not be on the path of a conversion, and a
 * deployment's cross-currency write path is dead without it. The switch is the module's, but the *work* is
 * conditional: no {@code reductxn.rates.feed} means no job, so a deployment that has not chosen a provider runs
 * exactly as it did before this annotation existed.
 */
@SpringBootApplication
@EnableScheduling
public class ReductxnApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReductxnApplication.class, args);
    }
}
