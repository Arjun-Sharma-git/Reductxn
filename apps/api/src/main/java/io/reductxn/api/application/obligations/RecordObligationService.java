package io.reductxn.api.application.obligations;

import io.reductxn.api.port.in.NewObligation;
import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordObligation;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.ChainHeads;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.api.port.out.JournalEntries;
import io.reductxn.api.port.out.ObligationStore;
import io.reductxn.api.port.out.ObligationToRecord;
import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.Commitment;
import io.reductxn.fx.api.FxException;
import io.reductxn.fx.api.FxRateProvider;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.planning.api.SingleCurrencyObligation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: record what a member promised, and book what that does to the ledger.
 *
 * <p><strong>It contains no rules.</strong> Which entry a commitment produces is {@code libs/booking}'s
 * decision, which entries a cycle produces is {@code libs/planning}'s, and what a rate does to an amount is
 * {@code libs/fx}'s. What is here is the sequence - check the request, resolve the currencies, ask for the
 * chain heads, book, append - plus the transaction that makes the chain heads meaningful, which RULE B15
 * keeps in this layer.
 *
 * <p><strong>This is the first write path in the product, and the first place a {@code Result} carries a
 * refusal out to a status.</strong> The order of operations is chosen so a refused request writes nothing:
 * every refusal is decided before the obligation row is inserted, and the row is inserted before the entry
 * is appended, so a duplicate key - the one thing a read-then-write replay cannot see coming - aborts the
 * transaction before anything is booked.
 *
 * <p><strong>{@code settleInCurrency} does not work yet, and it fails closed rather than quietly.</strong>
 * A rate comes from {@link FxRateProvider} and no provider is configured in any environment, so a
 * cross-currency request is answered {@code 503} with the provider's own {@code fx.rate_unavailable} - what
 * MASTER-PLAN section 4 means by "no rate means no cross-currency". What this class deliberately does not do
 * is book the pair: a cross-currency obligation's quote has nowhere to live until {@code fx_quotes} and the
 * obligation's quote columns exist, and recording one would store its debt side only - which the netting
 * preview would then read back as a single-currency obligation, silently, in the currency the creditor is
 * not being paid in.
 *
 * <p>Nothing here reads a clock: {@code occurredAt} arrives decided (RULE B21), which is what makes a request
 * for a past instant meaningful and a test reproducible.
 */
@Service
public class RecordObligationService implements RecordObligation {

    private final ObligationStore obligations;
    private final JournalEntries entries;
    private final ChainHeads chainHeads;
    private final CurrencyCatalog currencies;
    private final FxRateProvider rates;

    public RecordObligationService(
            ObligationStore obligations,
            JournalEntries entries,
            ChainHeads chainHeads,
            CurrencyCatalog currencies,
            FxRateProvider rates) {

        this.obligations = Objects.requireNonNull(obligations, "obligations");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.chainHeads = Objects.requireNonNull(chainHeads, "chainHeads");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.rates = Objects.requireNonNull(rates, "rates");
    }

    @Override
    @Transactional
    public Result<RecordedObligation> record(NewObligation request) {
        Objects.requireNonNull(request, "request");

        // The two refusals that are about the request being coherent, before anything is read or written.
        // Checked here rather than left to the domain types, whose guards throw: from their side a member
        // owing themselves means the code that built the obligation is wrong, and from this side it means the
        // client is.
        if (request.debtorId().equals(request.creditorId())) {
            return Result.fail(new ObligationRefusals.SameParty(request.debtorId()));
        }
        if (request.amountMinor() <= 0L) {
            return Result.fail(
                    new ObligationRefusals.AmountNotPositive(request.amountMinor(), request.currency()));
        }

        // A replay is answered with what is on the record, and books nothing. Checked before the currencies
        // are resolved, because a key that has been used identifies an obligation rather than a request: the
        // client is asking what happened, and it happened.
        Optional<RecordedObligation> already = obligations.findExisting(
                request.groupId(), request.idempotencyKey());

        if (already.isPresent()) {
            return Result.ok(already.get());
        }

        Currency debtCurrency = currencies.byCode(request.currency()).orElse(null);
        if (debtCurrency == null) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(request.currency()));
        }

        UUID obligationId = UUID.randomUUID();
        Commitment commitment = new Commitment(
                obligationId, request.groupId(), request.occurredAt(), request.idempotencyKey(),
                // No identity module yet, so nothing can say who submitted it. Null means "system-generated"
                // on the entry; a made-up id would be a claim about a member who does not exist.
                null);

        // Everything below uses `commitment.occurredAt()` rather than the request's instant. The commitment
        // truncates it to what the ledger can store, so the row, the entry and the response all carry the
        // value that was actually recorded - and a replayed request, whose response is read back from the
        // row, answers with the same instant rather than one that differs in the last few digits.
        return request.isCrossCurrency()
                ? crossCurrency(request, commitment, debtCurrency)
                : singleCurrency(request, commitment, debtCurrency);
    }
    /**
     * The ordinary case: one entry, debtor to creditor, in one currency.
     *
     * <p>{@code Bookings} is called statically rather than injected, unlike the planner in the preview's use
     * case. The planner is a bean because it is a choice between algorithms (ADR 0012); booking is a pure
     * function with one implementation, and a bean for it would be wiring that decides nothing.
     */
    private Result<RecordedObligation> singleCurrency(
            NewObligation request, Commitment commitment, Currency currency) {

        Money amount = Money.of(request.amountMinor(), currency);

        obligations.record(new ObligationToRecord(
                commitment.obligationId(), request.groupId(), request.debtorId(), request.creditorId(),
                amount, request.memo(), request.idempotencyKey(), commitment.occurredAt()));

        JournalEntry entry = Bookings.singleCurrency(
                chainHeads.forGroup(request.groupId()),
                commitment,
                new SingleCurrencyObligation(request.debtorId(), request.creditorId(), amount));

        entries.append(entry);

        return Result.ok(new RecordedObligation(
                commitment.obligationId(), request.groupId(), request.debtorId(), request.creditorId(),
                amount, currency, request.memo(), ObligationStatus.OPEN, commitment.occurredAt()));
    }

    /**
     * The cross-currency case, which asks for a rate and cannot yet store what the rate produced.
     *
     * <p>The port's failures are exceptions that implement {@code DomainError}, and they are caught and
     * returned as a value. That is not defensive coding: this module's {@code DomainErrorResolver} reads a
     * {@code DomainError} that escapes to the boundary as a programming fault and answers {@code 500} - right
     * for an invariant that was broken, wrong for a provider that could not be reached. Catching here is
     * where those two readings are separated, and it is why the resolver did not have to be loosened.
     *
     * <p>When a rate <em>is</em> available this still refuses, for the reason in the class comment: the quote
     * has nowhere to live. The code below makes booking the debt side alone impossible rather than merely
     * waiting for the storage.
     */
    private Result<RecordedObligation> crossCurrency(
            NewObligation request, Commitment commitment, Currency debtCurrency) {

        Currency settlementCurrency = currencies.byCode(request.settleInCurrency()).orElse(null);

        if (settlementCurrency == null) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(request.settleInCurrency()));
        }

        try {
            rates.rateFor(debtCurrency, settlementCurrency, request.occurredAt());
        } catch (FxException expected) {
            // The provider's own code and message, not a rewording: the client branches on the code, and the
            // adapter is the only place that knows whether the rate was stale, absent or unreachable.
            return Result.fail(expected);
        }

        // Unreachable today - no provider is configured, so the call above always fails - and deliberately
        // impossible to reach by accident. A cross-currency obligation needs its quote stored before it can
        // be recorded, and there is no `fx_quotes` table and no quote column on `obligations`.
        throw new IllegalStateException(
                "a rate for " + debtCurrency.code() + "/" + settlementCurrency.code() + " was available, so "
                        + "cross-currency recording is now reachable - and it must not be, because the locked "
                        + "quote has nowhere to live. `obligations` has no quote columns and there is no "
                        + "fx_quotes table, so this obligation would be stored with its debt side only, and "
                        + "the netting preview would read it back as a single-currency obligation in "
                        + debtCurrency.code() + " - the currency the creditor is not being paid in. Add the "
                        + "storage (fx_rates and fx_quotes in libs/fx, the quote columns and the read path in "
                        + "apps/api) in the same slice as the rate source. CrossCurrencyObligation and "
                        + "Bookings.crossCurrency are written and tested; what is missing is where the quote "
                        + "is kept.");
    }

}
