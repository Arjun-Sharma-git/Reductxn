import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery } from '@/entities/currency';
import { referenceRateQuery } from '@/entities/fx';
import { membersQuery } from '@/entities/member';
import type { ObligationFilters } from '@/entities/obligation';
import { useTranslations } from '@/shared/i18n';
import { formatRate, type CurrencyInfo } from '@/shared/money';
import { ProblemNotice } from '@/shared/ui';

import {
  amountOf,
  emptyForm,
  localRefusals,
  refusalFor,
  refusalsOf,
  worthRetrying,
  type RecordFormValues,
  type Refusal,
} from '../model/input';
import { nextAttemptKey, useRecordObligation } from '../mutations';
import { Field } from './Field';
import { fromLocalInput, toLocalInput } from '@/shared/lib/localTime';
import styles from './RecordObligationForm.module.css';

/**
 * Recording a commitment, which is the first thing this product lets a member *do*.
 *
 * Three things in here are not layout:
 *
 *   - **The amount never becomes a number with a fraction.** It stays text until `amountOf` turns it into integer
 *     minor units for the chosen currency's exponent, and over-precise input is refused rather than truncated
 *     (RULE F12, P1). The message says which limit was crossed.
 *   - **The idempotency key is form state, not a per-click value.** It is created when the form first renders and
 *     replaced only after a successful record, so pressing "try again" after a `503` repeats *the same attempt*
 *     rather than creating a second obligation (P8). That is what the button means, and it is why the retry is
 *     safe.
 *   - **A cross-currency choice states its cost before it is made.** ADR 0012's principle. The rate shown is
 *     indicative and labelled as such, because the quote that is actually locked is the server's decision at the
 *     obligation's instant; if the pair has no rate at all, the form says so before the submit - the same answer
 *     the server gives, one round trip earlier.
 */
export function RecordObligationForm({
  groupId,
  filters,
  catalog,
}: {
  readonly groupId: string;
  /** The view the member is looking at, so the optimistic row lands in it (see `mutations.ts`). */
  readonly filters: ObligationFilters;
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t, locale, dateTime } = useTranslations();

  const [values, setValues] = useState<RecordFormValues>(emptyForm);
  const [refusals, setRefusals] = useState<readonly Refusal[]>([]);
  const [attempt, setAttempt] = useState(nextAttemptKey);

  const members = useQuery(membersQuery(groupId));
  const currencies = useQuery(currencyCatalogQuery());
  const mutation = useRecordObligation(groupId, filters);

  const settlementDiffers =
    values.settleInCurrency !== '' && values.settleInCurrency !== values.currency;

  const rate = useQuery(referenceRateQuery(values.currency, values.settleInCurrency, settlementDiffers));

  // A refusal from the server lands beside the field that can fix it; the local ones were put there by the submit.
  const all = [...refusals, ...(mutation.isError ? refusalsOf(mutation.error) : [])];

  const set = (field: keyof RecordFormValues, value: string) =>
    setValues((current) => ({ ...current, [field]: value }));

  const submit = () => {
    const local = localRefusals(values, catalog, locale);

    if (local.length > 0) {
      setRefusals(local);

      return;
    }

    const amount = amountOf(values.amountText, values.currency, catalog, locale);

    if (!amount.ok) {
      setRefusals([{ field: 'amountText', code: amount.code }]);

      return;
    }

    setRefusals([]);

    mutation.mutate(
      {
        values: { ...values, occurredAt: fromLocalInput(values.occurredAt) },
        amount: amount.value,
        idempotencyKey: attempt,
      },
      {
        onSuccess: () => {
          // The next commitment is a different attempt, and the amount and the reason were about the one just
          // recorded. Who owes whom and in what currency usually does not change, so those are kept.
          setValues((current) => ({
            ...emptyForm(),
            debtorId: current.debtorId,
            creditorId: current.creditorId,
            currency: current.currency,
            settleInCurrency: current.settleInCurrency,
          }));
          setAttempt(nextAttemptKey());
        },
      },
    );
  };

  const formRefusal = refusalFor(all, 'form');

  return (
    <form
      className={styles.form}
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <h2 className={styles.title}>{t('obligations.form.title')}</h2>
      <p className={styles.description}>{t('obligations.form.description')}</p>

      {formRefusal === undefined ? null : (
        <p className={styles.formError} role="alert">
          {t(`errors.${formRefusal.code}`)}
        </p>
      )}

      <Field
        id="debtorId"
        label={t('obligations.form.debtor')}
        hint={t('obligations.form.debtorHint')}
        refusal={refusalFor(all, 'debtorId')}
      >
        <select
          id="debtorId"
          className={styles.control}
          aria-invalid={refusalFor(all, 'debtorId') !== undefined}
          aria-describedby={refusalFor(all, 'debtorId') === undefined ? undefined : 'debtorId-error'}
          value={values.debtorId}
          onChange={(event) => set('debtorId', event.target.value)}
        >
          <option value="">{t('obligations.form.chooseMember')}</option>
          {(members.data ?? []).map((member) => (
            <option key={member.userId} value={member.userId}>
              {member.displayName}
            </option>
          ))}
        </select>
      </Field>

      <Field
        id="creditorId"
        label={t('obligations.form.creditor')}
        refusal={refusalFor(all, 'creditorId')}
      >
        <select
          id="creditorId"
          className={styles.control}
          aria-invalid={refusalFor(all, 'creditorId') !== undefined}
          aria-describedby={refusalFor(all, 'creditorId') === undefined ? undefined : 'creditorId-error'}
          value={values.creditorId}
          onChange={(event) => set('creditorId', event.target.value)}
        >
          <option value="">{t('obligations.form.chooseMember')}</option>
          {(members.data ?? []).map((member) => (
            <option key={member.userId} value={member.userId}>
              {member.displayName}
            </option>
          ))}
        </select>
      </Field>

      <Field
        id="amountText"
        label={t('obligations.form.amount')}
        hint={t('obligations.form.amountHint')}
        refusal={refusalFor(all, 'amountText')}
      >
        <div className={styles.amountRow}>
          <input
            id="amountText"
            className={styles.control}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            aria-invalid={refusalFor(all, 'amountText') !== undefined}
            aria-describedby={refusalFor(all, 'amountText') === undefined ? undefined : 'amountText-error'}
            value={values.amountText}
            onChange={(event) => set('amountText', event.target.value)}
          />
          <select
            id="currency"
            aria-label={t('obligations.form.currency')}
            className={styles.currency}
            value={values.currency}
            onChange={(event) => set('currency', event.target.value)}
          >
            <option value="">{t('obligations.form.chooseCurrency')}</option>
            {(currencies.data ?? []).map((info) => (
              <option key={info.code} value={info.code}>
                {info.code}
              </option>
            ))}
          </select>
        </div>
      </Field>

      <Field
        id="settleInCurrency"
        label={t('obligations.form.settleIn')}
        hint={t('obligations.form.settleInHint')}
        refusal={refusalFor(all, 'settleInCurrency')}
      >
        <select
          id="settleInCurrency"
          className={styles.control}
          aria-invalid={refusalFor(all, 'settleInCurrency') !== undefined}
          aria-describedby={
            refusalFor(all, 'settleInCurrency') === undefined ? undefined : 'settleInCurrency-error'
          }
          value={values.settleInCurrency}
          onChange={(event) => set('settleInCurrency', event.target.value)}
        >
          <option value="">{t('obligations.form.settleInSame')}</option>
          {(currencies.data ?? []).map((info) => (
            <option key={info.code} value={info.code}>
              {info.code}
            </option>
          ))}
        </select>
      </Field>

      {/*
        The cost of the currency choice, before it is made. Indicative and labelled as such: the quote that will
        actually be locked is the server's decision at the obligation's instant, and claiming otherwise would be
        the screen promising a number it does not control.
      */}
      {settlementDiffers ? (
        <div className={styles.rate} role="status">
          {rate.isSuccess ? (
            <p className={styles.rateLine}>
              {t('obligations.form.indicativeRate', {
                base: rate.data.base,
                quote: rate.data.quote,
                rate: formatRate(rate.data.rateScaled, rate.data.rateScale, locale),
                quotedAt: dateTime(rate.data.quotedAt),
              })}
            </p>
          ) : null}
          {rate.isPending ? <p className={styles.rateLine}>{t('obligations.form.rateLoading')}</p> : null}
          {rate.isError ? <ProblemNotice fault={rate.error} /> : null}
        </div>
      ) : null}

      <Field
        id="memo"
        label={t('obligations.form.memo')}
        hint={t('obligations.form.memoHint')}
        refusal={refusalFor(all, 'memo')}
      >
        <textarea
          id="memo"
          className={styles.control}
          rows={2}
          maxLength={280}
          aria-invalid={refusalFor(all, 'memo') !== undefined}
          aria-describedby={refusalFor(all, 'memo') === undefined ? undefined : 'memo-error'}
          value={values.memo}
          onChange={(event) => set('memo', event.target.value)}
        />
      </Field>

      <Field
        id="occurredAt"
        label={t('obligations.form.occurredAt')}
        hint={t('obligations.form.occurredAtHint')}
        refusal={refusalFor(all, 'occurredAt')}
      >
        <input
          id="occurredAt"
          className={styles.control}
          type="datetime-local"
          value={toLocalInput(values.occurredAt)}
          onChange={(event) => set('occurredAt', fromLocalInput(event.target.value))}
        />
      </Field>

      <button className="rn-button" type="submit" disabled={mutation.isPending}>
        {mutation.isError && worthRetrying(mutation.error)
          ? t('obligations.form.tryAgain')
          : t('obligations.form.submit')}
      </button>

      {mutation.isPending ? (
        <p className={styles.status} role="status">
          {t('obligations.form.recording')}
        </p>
      ) : null}

      {/*
        A refusal that will not change if it is repeated says so, rather than leaving a member pressing a button.
        This is the 422-versus-503 distinction from the contract, stated in the interface.
      */}
      {mutation.isError && !worthRetrying(mutation.error) ? (
        <p className={styles.status}>{t('obligations.form.notWorthRetrying')}</p>
      ) : null}
    </form>
  );
}
