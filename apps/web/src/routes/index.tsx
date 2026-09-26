import { createFileRoute, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { z } from 'zod';

import { useTranslations } from '@/shared/i18n';

/**
 * The way in: a group id, pasted.
 *
 * There is no group list to show and no sign-in to gate on - `groups` does not exist as a table yet and identity
 * is a later phase - so this screen is honest about what a deployment can do today: a member who has been given
 * a group's identifier can open its plan. It is the placeholder that RULE F4's "thin route" shape makes cheap to
 * replace with a real group list, and it means the preview screen is reachable without a hand-typed URL.
 *
 * The identifier is validated as a UUID here rather than being sent and refused: a 404 from the API for a typo is
 * a worse experience than a message beside the field, and the skill's form rules ask for the error to be near
 * the input that caused it.
 */
const search = z.object({ lang: z.string().optional() });

const groupId = z.string().uuid();

export const Route = createFileRoute('/')({
  validateSearch: search,
  component: OpenGroup,
});

function OpenGroup() {
  const { t } = useTranslations();
  const navigate = useNavigate();
  const [value, setValue] = useState('');
  const [invalid, setInvalid] = useState(false);

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();

        const parsed = groupId.safeParse(value.trim());

        if (!parsed.success) {
          setInvalid(true);

          return;
        }

        void navigate({
          to: '/groups/$groupId/netting',
          params: { groupId: parsed.data },
        });
      }}
    >
      <h1>{t('common.openGroup.title')}</h1>
      <p>{t('common.openGroup.description')}</p>

      <label className="rn-field">
        <span>{t('common.openGroup.groupId')}</span>
        <input
          className="rn-input"
          type="text"
          name="groupId"
          autoComplete="off"
          spellCheck={false}
          aria-invalid={invalid}
          aria-describedby={invalid ? 'groupId-error' : undefined}
          value={value}
          onChange={(event) => {
            setValue(event.target.value);
            setInvalid(false);
          }}
        />
      </label>

      {invalid ? (
        <p id="groupId-error" role="alert">
          {t('common.openGroup.invalid')}
        </p>
      ) : null}

      <button className="rn-button" type="submit">
        {t('common.openGroup.submit')}
      </button>
    </form>
  );
}
