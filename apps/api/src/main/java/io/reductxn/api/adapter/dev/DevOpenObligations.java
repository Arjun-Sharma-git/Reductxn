package io.reductxn.api.adapter.dev;

import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.planning.api.CycleObligation;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A development double for {@link OpenObligations}: obligations held in memory.
 *
 * <p><strong>Development only, and the profile is what enforces that.</strong> Its sibling classes in
 * {@code adapter/persistence} will be the real ones, and this package exists to keep the two from being
 * confused in a code review - a double called {@code InMemoryRepository} sitting in
 * {@code adapter/persistence} reads like persistence that exists.
 *
 * <p>It cannot be deployed by accident: the doubles are behind the {@code dev} profile, the use case is
 * not, so the default profile has no {@code OpenObligations} bean at all and the context refuses to
 * start. {@code MissingStoreFailsClosedTest} asserts that refusal, because a stub that can reach an
 * environment is worse than no stub.
 *
 * <p><strong>What it cannot do:</strong> {@code asOf} is accepted and ignored. This store has no
 * effective dating, so it cannot answer "what did this look like last Tuesday" - a question the port
 * exists to allow. That is a limitation of the double rather than of the port, and it is stated here
 * because a caller who trusted it would get a right-looking answer to the wrong question.
 */
@Component
@Profile("dev")
public class DevOpenObligations implements OpenObligations {

    private final Map<UUID, List<CycleObligation>> obligationsByGroup = new ConcurrentHashMap<>();

    /**
     * Replaces what this store holds for a group.
     *
     * <p>Public because seeding is the only way to exercise the preview before a real store exists, and a
     * test that had to reflect into a private field to do it would be testing a different thing.
     */
    public void seed(UUID groupId, List<CycleObligation> obligations) {
        obligationsByGroup.put(groupId, List.copyOf(obligations));
    }

    @Override
    public List<CycleObligation> openFor(UUID groupId, Instant asOf) {
        return obligationsByGroup.getOrDefault(groupId, List.of());
    }
}
