import { problemOf } from '@/shared/api/client';
import { money, parseAmount, type CurrencyInfo, type Money } from '@/shared/money';
import { ok, refuse, type Result } from '@/shared/lib/result';

/**
 * What a member typed, as a request - or the refusals that say what is wrong with it.
 *
 * Pure functions, no React: this is the part of a form that can be wrong in ways a browser test would take
 * seconds to find and a unit test finds in milliseconds. The component is then layout and state.
 *
 * **Two rules are enforced locally with the server's own codes.** A debtor cannot be the creditor, and a request
 * needs both members - both are checked here with `api.obligation_same_party` and
 * `api.obligation_request_incomplete`, the codes the server answers with. Using the server's code rather than
 * inventing a client one is deliberate: the member sees one message for one rule, whether it was caught early or
 * answered late, and the catalog does not grow a second sentence for the same rule. It is a check *before* the
 * request, not a copy of the server's validation: anything else the server refuses arrives as a problem and is
 * mapped by {@link refusalsOf}.
 *
 * **The amount is parsed, never floated.** `parseAmount` (RULE F12) turns the typed text into integer minor units
 * for the chosen currency's exponent, and refuses over-precise input rather than truncating it. A refusal here
 * means nothing was sent.
 */

export type RecordFormValues = {
  readonly debtorId: string;
  readonly creditorId: string;
  readonly amountText: string;
  readonly currency: string;
  /** Empty means the creditor is paid in `currency` - the common case, and not a second choice to make. */
  readonly settleInCurrency: string;
  readonly memo: string;
  readonly occurredAt: string;
};

export type FieldName =
  | 'debtorId'
  | 'creditorId'
  | 'amountText'
  | 'settleInCurrency'
  | 'memo'
  | 'occurredAt';

export type Refusal = {
  /** Which control to put the message under, or the form as a whole. */
  readonly field: FieldName | 'form';
  readonly code: string;
  readonly params?: Readonly<Record<string, string | number>>;
};

export const emptyForm = (): RecordFormValues => ({
  debtorId: '',
  creditorId: '',
  amountText: '',
  currency: '',
  settleInCurrency: '',
  memo: '',
  occurredAt: new Date().toISOString(),
});

/** The typed amount as money, or a refusal the caller keys to the field it came from. */
export function amountOf(
  text: string,
  currency: string,
  catalog: ReadonlyMap<string, CurrencyInfo>,
  locale: string,
): Result<Money> {
  if (currency === '') {
    return refuse('api.obligation_request_incomplete');
  }

  const info = catalog.get(currency);

  if (info === undefined) {
    return refuse('api.obligation_unknown_currency', { currency });
  }

  const parsed = parseAmount(text, locale, info.minorUnitExponent);

  if (!parsed.ok) {
    return parsed;
  }

  if (parsed.value === 0) {
    return refuse('api.obligation_amount_not_positive');
  }

  return ok(money(parsed.value, currency));
}

/** Everything that can be decided without asking the server. */
export function localRefusals(
  values: RecordFormValues,
  catalog: ReadonlyMap<string, CurrencyInfo>,
  locale: string,
): readonly Refusal[] {
  if (values.debtorId === '' || values.creditorId === '') {
    return [
      {
        field: values.debtorId === '' ? 'debtorId' : 'creditorId',
        code: 'api.obligation_request_incomplete',
      },
    ];
  }

  if (values.debtorId === values.creditorId) {
    return [{ field: 'creditorId', code: 'api.obligation_same_party' }];
  }

  const amount = amountOf(values.amountText, values.currency, catalog, locale);

  if (!amount.ok) {
    return [
      {
        field: 'amountText',
        code: amount.code,
        ...(amount.params === undefined ? {} : { params: amount.params }),
      },
    ];
  }

  if (values.memo.trim() === '') {
    // The contract requires a memo. A commitment without a stated reason is one nobody can adjudicate later, and
    // the ledger has no field for "we will explain it afterwards".
    return [{ field: 'memo', code: 'api.obligation_request_incomplete' }];
  }

  if (Number.isNaN(Date.parse(values.occurredAt))) {
    return [{ field: 'occurredAt', code: 'form.occurred_at_invalid' }];
  }

  return [];
}

/**
 * A server refusal, as fields to mark.
 *
 * Every code the record endpoint can answer is here, and each is mapped to the control that can fix it - which is
 * the skill's eighth priority ("the error belongs beside the field") applied to a server that answers with codes
 * rather than prose (RULE B20). Two of those mappings carry the distinction the contract draws explicitly: a
 * `422 fx.rate_stale` is an answer that this request cannot be honoured, and a `503 fx.rate_unavailable` is "not
 * yet" - so both are marked on the currency control, and only the second one offers a retry.
 *
 * A code with no obvious field is shown on the form: it is still the server's answer, and hiding it would leave a
 * member looking at a button that did nothing.
 */
export function refusalsOf(fault: unknown): readonly Refusal[] {
  const problem = problemOf(fault);

  switch (problem.code) {
    case 'api.obligation_amount_not_positive':
      return [{ field: 'amountText', code: problem.code }];

    case 'api.obligation_same_party':
      return [{ field: 'creditorId', code: problem.code }];

    case 'api.obligation_unknown_currency':
    case 'ledger.currency_mismatch':
    case 'fx.rate_stale':
    case 'fx.rate_unavailable':
      return [{ field: 'settleInCurrency', code: problem.code, params: problem.params }];

    case 'api.obligation_memo_too_long':
      return [{ field: 'memo', code: problem.code }];

    case 'api.obligation_request_incomplete':
    case 'api.request_incomplete':
      return [{ field: 'form', code: problem.code }];

    default:
      return [{ field: 'form', code: problem.code, params: problem.params }];
  }
}

/** The refusal about one field, which is how the component asks for it. */
export function refusalFor(
  refusals: readonly Refusal[],
  field: FieldName | 'form',
): Refusal | undefined {
  return refusals.find((refusal) => refusal.field === field);
}

/**
 * Whether a failure is worth trying again.
 *
 * The contract's own distinction, in one function: `503 fx.rate_unavailable` means "the server cannot answer yet,
 * retry later", and a network failure is the same shape of thing. A `422` means the request was answerable and the
 * answer is no - retrying it is asking a refused question twice, which is load rather than resilience.
 *
 * The retry is safe rather than merely hopeful because it carries the *same* idempotency key: if the first attempt
 * actually succeeded and its response was lost on the way back, the retry collides with the recorded obligation
 * instead of creating a second one (P8).
 */
export function worthRetrying(fault: unknown): boolean {
  const { status } = problemOf(fault);

  return status === 503 || status === 0;
}
