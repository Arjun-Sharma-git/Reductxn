package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadCurrentUser;
import io.reductxn.api.port.in.SignInRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.BearerToken;
import io.reductxn.api.web.dto.UserResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code GET /v1/me}: who is asking.
 *
 * <p><strong>This is the operation the client has been working around since its first screen.</strong> Without it there is
 * no way to know which member a person is, so every screen that names somebody fell back to showing a uuid, and the
 * members read looked deliberate rather than missing for seven slices (ADR 0035, 0038). It is also what makes a token mean
 * something: before this endpoint exists, a token is a string nothing checks.
 *
 * <p><strong>No credential is a {@code 401}, and the refusal is the use case's.</strong> The header is read here because
 * reading headers is a boundary's job; whether the token names a live session is not, and this class never decides it. A
 * request with no {@code Authorization} header is answered exactly like one with a token nobody issued - because from the
 * outside those are the same request, and telling them apart would tell a caller whether a token was ever real.
 *
 * <p><strong>The contract's global security finally means what it says.</strong> Every operation in this document is
 * declared {@code bearerAuth} at the top and the auth operations opt out with {@code security: []}; until now nothing
 * behind that declaration read a token. This operation does - and what it deliberately does not do is enforce the
 * declaration on the other boundaries, which is a decision of its own rather than a line of code: requiring tokens
 * everywhere would break every existing caller at once, and it is recorded as the next slice rather than smuggled into
 * this one (ADR 0042).
 */
@RestController
@RequestMapping("/v1/me")
public class MeController {

    private final ReadCurrentUser readCurrentUser;

    public MeController(ReadCurrentUser readCurrentUser) {
        this.readCurrentUser = Objects.requireNonNull(readCurrentUser, "readCurrentUser");
    }

    @GetMapping
    public ResponseEntity<Object> read(HttpServletRequest http) {
        Optional<String> token = BearerToken.from(http);

        if (token.isEmpty()) {
            return problem(new SignInRefusals.NotSignedIn(), http.getRequestURI());
        }

        Result<SignedInUser> user = readCurrentUser.read(token.get());

        if (user instanceof Result.Ok<SignedInUser> found) {
            return ResponseEntity.ok(UserResponse.of(found.value()));
        }

        return problem(((Result.Failure<SignedInUser>) user).error(), http.getRequestURI());
    }

    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
