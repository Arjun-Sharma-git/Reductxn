package io.reductxn.api.web.error;

import io.reductxn.api.port.in.DecisionRefusals;
import io.reductxn.api.port.in.InviteRefusals;
import io.reductxn.api.port.in.MemberRefusals;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.fx.api.AmountTooSmallToConvertException;
import io.reductxn.fx.api.StaleRateException;
import io.reductxn.fx.api.UnavailableRateException;
import io.reductxn.kernel.api.DomainError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.Map;

/**
 * The status an expected domain failure deserves, decided in one place.
 *
 * <p>This is the path {@code DomainErrorResolver} documented as having no cases yet: an expected failure
 * travels as a {@link io.reductxn.kernel.api.Result} value rather than as an exception, and something has to
 * turn it into a response. That something is here, and it is separate from the resolver because the two do
 * different jobs - the resolver answers "this escaped and it is a bug", this answers "the domain said no,
 * which is an answer".
 *
 * <p><strong>The map is exact, and an unmapped code is a {@code 500}.</strong> Matching on a code's namespace
 * instead - {@code fx.*} is 422, {@code obligation.*} is 422 - would have been shorter and is wrong twice
 * over: {@code fx.rate_unavailable} is a 503 while its own namespace's other failures are 422s, and a code
 * nobody had thought about would silently acquire a status. Failing loudly means the next code has to be
 * given a status deliberately, which is the same discipline the contract applies to codes themselves.
 *
 * <p>{@code ProblemTranslator} does the rest, so a code is validated and the fields are the contract's
 * whether the failure was thrown or returned.
 */
public final class ExpectedFailures {

    private static final Logger LOG = LoggerFactory.getLogger(ExpectedFailures.class);

    // The 422 codes are written as UNPROCESSABLE_CONTENT because Spring 7 deprecated UNPROCESSABLE_ENTITY in
    // favour of RFC 9110's name for the same status. The wire code is 422 either way, which is what the
    // contract lists.
    private static final Map<String, HttpStatus> STATUS_BY_CODE = Map.ofEntries(
            // The request itself is malformed, so the server has not begun to answer it.
            Map.entry(ObligationRefusals.RequestIncomplete.CODE, HttpStatus.BAD_REQUEST),
            Map.entry(ObligationRefusals.MemoTooLong.CODE, HttpStatus.BAD_REQUEST),
            Map.entry(MemberRefusals.NameTooLong.CODE, HttpStatus.BAD_REQUEST),
            Map.entry(RunRefusals.RequestIncomplete.CODE, HttpStatus.BAD_REQUEST),

            // The request is understood and the answer is no: an obligation that cannot exist, or a
            // conversion that is not possible at all.
            Map.entry(ObligationRefusals.SameParty.CODE, HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(ObligationRefusals.AmountNotPositive.CODE, HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(ObligationRefusals.UnknownCurrency.CODE, HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(StaleRateException.CODE, HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(AmountTooSmallToConvertException.CODE, HttpStatus.UNPROCESSABLE_CONTENT),

            // The run's own rows refused the answer. 422 rather than 409: the request conflicts with nothing that
            // exists, it asks for something the run cannot hold - and a client that retried it would get the same
            // answer, which is what separates it from the conflict below.
            Map.entry(DecisionRefusals.NotAcceptable.CODE, HttpStatus.UNPROCESSABLE_CONTENT),

            // There is nothing to answer, which is not the same as answering nothing: a client that got this for a
            // malformed body would go looking for an id that was correct all along.
            Map.entry(RunRefusals.NoSuchRun.CODE, HttpStatus.NOT_FOUND),

            // A code nobody holds. 404 rather than the 410 below, and the difference is what a client does next: a
            // mistyped code is a code for nothing, while an invitation that ran out is one that existed and is spent.
            Map.entry(InviteRefusals.NoSuchInvite.CODE, HttpStatus.NOT_FOUND),

            // Gone, which is what the contract has said for "invite expired or exhausted" since the path was added.
            // Two codes under one status because the conditions differ and the remedy does not - ask the group for a
            // current code - so a client can say which one ran out without a second status to branch on.
            Map.entry(InviteRefusals.InviteExpired.CODE, HttpStatus.GONE),
            Map.entry(InviteRefusals.InviteExhausted.CODE, HttpStatus.GONE),

            // A conflict with what is already on the record: this member has answered this run, and an answer is
            // final. 409 rather than 422, because the request is well-formed and could have succeeded earlier - the
            // state of the world is what refuses it.
            Map.entry(DecisionRefusals.AlreadyAnswered.CODE, HttpStatus.CONFLICT),

            // The server cannot answer yet. A retry may succeed, which is the opposite of the 422 above and
            // the reason they are not one status: a client that conflated them would give up on a request
            // that would work later, or retry one that never will.
            Map.entry(UnavailableRateException.CODE, HttpStatus.SERVICE_UNAVAILABLE));

    private ExpectedFailures() {
    }

    public static ProblemDetail toProblem(DomainError failure, String instance) {
        HttpStatus status = STATUS_BY_CODE.get(failure.code());

        if (status == null) {
            LOG.error("the expected failure {} has no status in ExpectedFailures, so it is being answered as "
                            + "a 500. Add it to STATUS_BY_CODE and to the contract's response list - a code "
                            + "is contract surface (RULE B20), and a client cannot branch on one whose status "
                            + "was never decided",
                    failure.code());
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        return ProblemTranslator.toProblem(failure, status, instance);
    }
}
