import { useState } from 'react';

import { useTranslations } from '@/shared/i18n';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { deviceId, issueWith, type SignInStep } from '../model/signIn';
import { useRequestCode, useVerifyCode } from '../mutations';
import styles from './SignInScreen.module.css';

/**
 * Signing in: a phone number, then the code that proves it.
 *
 * **Two steps because the API has two operations**, and the screen has no third to invent: the first asks for a code, the
 * second hands it back and receives a session. Keeping them on one screen rather than two routes is what makes "we sent a
 * code to +91 …" showable - the number is in the component's state, and a second route would have to carry it in the URL,
 * which is a phone number in a URL a person can share by accident.
 *
 * **Every translation key is written out rather than built from the step's name.** A key composed at runtime
 * (`t(\`sign-in.${step}.title\`)`) is a key no tool can see, and this repository's catalogue check reports what it cannot
 * see as unused - correctly, because a key nobody asks for by name is a key nobody can prove is used. Two small blocks of
 * literal keys cost a few lines and keep the catalogues checkable (RULE I1, I2).
 *
 * **It says where the code goes.** There is no SMS provider in this deployment, so the code is in the server log
 * (ADR 0042) - and a screen that said "check your messages" would be telling a person to look somewhere it never went.
 * What it says instead is exactly where to look, which is the difference between a stand-in that works and a stand-in that
 * wastes somebody's afternoon. The code is never shown here even though the server is what writes it: a screen displaying
 * it would make the log line redundant and the whole scheme pointless.
 */
export function SignInScreen() {
  const { t } = useTranslations();

  const [step, setStep] = useState<SignInStep>('phone');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [code, setCode] = useState('');
  const [askedFor, setAskedFor] = useState('');

  const requestCode = useRequestCode();
  const verifyCode = useVerifyCode();

  const issue = issueWith(step, step === 'phone' ? phoneNumber : code);

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('signIn.title')}</h1>
        <p className={styles.lede}>{t('signIn.lede')}</p>
      </header>

      {step === 'phone' ? (
        <Card title={t('signIn.phone.title')} description={t('signIn.phone.description')}>
          {requestCode.isError ? <ProblemNotice fault={requestCode.error} /> : null}

          <form
            className={styles.form}
            onSubmit={(event) => {
              event.preventDefault();

              if (issue !== null) {
                return;
              }

              const number = phoneNumber.trim();

              requestCode.mutate(number, {
                onSuccess: () => {
                  setAskedFor(number);
                  setStep('code');
                },
              });
            }}
          >
            <label className={styles.field}>
              {t('signIn.phone.label')}
              <input
                type="tel"
                value={phoneNumber}
                onChange={(event) => setPhoneNumber(event.target.value)}
                autoComplete="tel"
              />
            </label>

            <button type="submit" disabled={requestCode.isPending || issue !== null}>
              {requestCode.isPending ? t('signIn.phone.busy') : t('signIn.phone.submit')}
            </button>
          </form>
        </Card>
      ) : (
        <Card title={t('signIn.code.title')} description={t('signIn.code.description')}>
          <Notice>{t('signIn.code.where', { phoneNumber: askedFor })}</Notice>

          {verifyCode.isError ? <ProblemNotice fault={verifyCode.error} /> : null}

          <form
            className={styles.form}
            onSubmit={(event) => {
              event.preventDefault();

              if (issue === null) {
                verifyCode.mutate({ phoneNumber: askedFor, code: code.trim(), deviceId: deviceId() });
              }
            }}
          >
            <label className={styles.field}>
              {t('signIn.code.label')}
              <input
                type="text"
                inputMode="numeric"
                maxLength={6}
                value={code}
                onChange={(event) => setCode(event.target.value)}
                autoComplete="one-time-code"
              />
            </label>

            <button type="submit" disabled={verifyCode.isPending || issue !== null}>
              {verifyCode.isPending ? t('signIn.code.busy') : t('signIn.code.submit')}
            </button>

            <button
              type="button"
              className={styles.aside}
              onClick={() => {
                setCode('');
                setStep('phone');
              }}
            >
              {t('signIn.code.other')}
            </button>
          </form>
        </Card>
      )}
    </div>
  );
}
