import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { taxReportQuery, taxRulesQuery } from '@/entities/tax';
import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Amount, Card, Notice, ProblemNotice } from '@/shared/ui';

import { isInverted, ruleParams } from '../model/tax';
import styles from './TaxScreen.module.css';

/**
 * The tax screen: what this group is charged under, and what its plans have assessed.
 *
 * It is the last screen of MASTER-PLAN section 6's route tree, and both of its halves exist because the contract
 * promised them and nothing answered (ADR 0038 recorded the gaps; ADR 0040 closed them).
 *
 * **The two halves are deliberately not merged into one number.** The rules read is reference data - the rates in force
 * for this deployment, with the statute each one encodes - and the report is the plans' own arithmetic. A screen
 * showing a single "tax owed" would have to add rupees to yen (ADR 0012), and the operation it calls deliberately
 * offers no reporting currency to do it with.
 *
 * **What the report is, said out loud.** These figures are what the group's *plans* assess, not tax that has been
 * collected or remitted. `docs/specs/tax-rules.md` section 7 leaves the withholding question open with a default
 * posture of compute-and-report, and a screen about tax that let a reader assume the second thing had happened would be
 * the most expensive misunderstanding this product could produce - so the card says which it is.
 *
 * **The parameters are shown, not interpreted.** The client does not implement the engine, so it lists the keys and
 * values each rule carries rather than translating a basis-point rate into a percentage whose unit it was never told.
 */
export function TaxScreen({
  groupId,
  period,
  onPeriodChange,
}: {
  readonly groupId: string;
  readonly period: { readonly start?: string; readonly end?: string };
  readonly onPeriodChange: (next: { readonly periodStart?: string; readonly periodEnd?: string }) => void;
}) {
  const { t } = useTranslations();

  // The form holds its own state until it is applied: a half-typed date is not a question, and the URL should carry
  // what the reader asked for rather than what they are still typing.
  const [start, setStart] = useState(period.start ?? '');
  const [end, setEnd] = useState(period.end ?? '');

  const backwards = isInverted(start === '' ? undefined : start, end === '' ? undefined : end);

  const rules = useQuery(taxRulesQuery());
  const report = useQuery(taxReportQuery(groupId, period.start, period.end));
  const currencies = useQuery(currencyCatalogQuery());

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);

  const ruleRows = rules.data ?? [];
  const lines = report.data ?? [];

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('tax.title')}</h1>
        <p className={styles.lede}>{t('tax.lede')}</p>
      </header>

      {currencies.isError ? <ProblemNotice fault={currencies.error} /> : null}
      {rules.isError ? <ProblemNotice fault={rules.error} /> : null}

      <Card title={t('tax.rules.cardTitle')} description={t('tax.rules.cardDescription')}>
        {rules.isPending ? (
          <p className={styles.status} role="status">
            {t('tax.loading')}
          </p>
        ) : null}

        {rules.isSuccess && ruleRows.length === 0 ? <Notice>{t('tax.rules.empty')}</Notice> : null}

        {ruleRows.length > 0 ? (
          <table className={styles.table}>
            <caption className={styles.caption}>{t('tax.rules.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('tax.rules.jurisdiction')}</th>
                <th scope="col">{t('tax.rules.currency')}</th>
                <th scope="col">{t('tax.rules.kind')}</th>
                <th scope="col">{t('tax.rules.params')}</th>
                <th scope="col">{t('tax.rules.effectiveFrom')}</th>
                <th scope="col">{t('tax.rules.effectiveTo')}</th>
                <th scope="col">{t('tax.rules.sourceRef')}</th>
              </tr>
            </thead>
            <tbody>
              {ruleRows.map((rule) => (
                <tr key={rule.id}>
                  <td>{rule.jurisdiction}</td>
                  <td>{rule.currency}</td>
                  <td>{t(`tax.kind.${rule.kind}`)}</td>
                  <td>
                    <span className={styles.parameters}>
                      {ruleParams(rule).map((param) => (
                        <span key={param.key}>
                          <span className={styles.parameterKey}>{param.key}</span>
                          {': '}
                          {param.value}
                        </span>
                      ))}
                    </span>
                  </td>
                  {/* Rendered in the reader's own zone, as every instant in this client is: the server sends an
                      instant, and the wall clock a member reads is theirs (shared/lib/localTime). An end that is
                      absent or empty is the same answer - a rule with no end - so both take the same branch. */}
                  <td>{toLocalInput(rule.effectiveFrom)}</td>
                  <td>{rule.effectiveTo ? toLocalInput(rule.effectiveTo) : t('tax.rules.noEnd')}</td>
                  <td>{rule.sourceRef}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Card>

      {report.isError ? <ProblemNotice fault={report.error} /> : null}

      <Card title={t('tax.report.cardTitle')} description={t('tax.report.cardDescription')}>
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            onPeriodChange({
              periodStart: start === '' ? undefined : start,
              periodEnd: end === '' ? undefined : end,
            });
          }}
        >
          <label className={styles.field}>
            {t('tax.report.from')}
            <input type="date" value={start} onChange={(event) => setStart(event.target.value)} />
          </label>
          <label className={styles.field}>
            {t('tax.report.to')}
            <input type="date" value={end} onChange={(event) => setEnd(event.target.value)} />
          </label>
          <button type="submit" disabled={backwards}>
            {t('tax.report.apply')}
          </button>
          <button
            type="button"
            onClick={() => {
              setStart('');
              setEnd('');
              onPeriodChange({ periodStart: undefined, periodEnd: undefined });
            }}
          >
            {t('tax.report.clear')}
          </button>
        </form>

        {/* Said before the request rather than after an empty table: a range that ends before it starts cannot contain
            a plan, and the reader is looking at the form that would ask for it. The API answers such a range with an
            empty report rather than an error, so this is a courtesy rather than a refusal. */}
        {backwards ? <Notice tone="warning">{t('tax.report.backwards')}</Notice> : null}

        {report.isPending ? (
          <p className={styles.status} role="status">
            {t('tax.loading')}
          </p>
        ) : null}

        {report.isSuccess && lines.length === 0 ? <Notice>{t('tax.report.empty')}</Notice> : null}

        {lines.length > 0 ? (
          <>
            <table className={styles.table}>
              <caption className={styles.caption}>{t('tax.report.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('tax.report.currency')}</th>
                  <th scope="col" className={styles.numeric}>
                    {t('tax.report.assessed')}
                  </th>
                  <th scope="col" className={styles.numeric}>
                    {t('tax.report.saved')}
                  </th>
                  <th scope="col" className={styles.numeric}>
                    {t('tax.report.gross')}
                  </th>
                  <th scope="col" className={styles.numeric}>
                    {t('tax.report.plans')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line) => (
                  <tr key={line.currency}>
                    <th scope="row">{line.currency}</th>
                    <td className={styles.numeric}>
                      <Amount value={line.netTax} catalog={catalog} />
                    </td>
                    <td className={styles.numeric}>
                      <Amount value={line.taxSaved} catalog={catalog} />
                    </td>
                    <td className={styles.numeric}>
                      <Amount value={line.grossTax} catalog={catalog} />
                    </td>
                    <td className={styles.numeric}>{line.runCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className={styles.footnote}>{t('tax.report.perCurrencyNote')}</p>
          </>
        ) : null}
      </Card>
    </div>
  );
}
