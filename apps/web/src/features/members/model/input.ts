import { problemOf } from '@/shared/api/client';
import { ok, refuse, type Result } from '@/shared/lib/result';

import type { NewMember } from '@/entities/member';

/**
 * What a member typed, as a request - or the refusals that say what is wrong with it.
 *
 * Pure functions, no React, for the reason the obligations model gives: this is the part of a form that can be wrong,
 * and a unit test finds it in milliseconds where a browser test takes seconds.
 *
 * **The checks here are courtesies, and the server is the authority.** A malformed user id and an empty name are
 * refused locally so the member sees the problem without a round trip, using the server's own codes
 * (`api.request_incomplete`) so one rule has one message. Everything else - a role the API does not store, a name that
 * is exactly at the boundary - is answered by the API, and {@link refusalsOf} maps that answer back to a field.
 *
 * **The role is a union here, and that is the one place this client enumerates a vocabulary.** The general rule is
 * the opposite - a client that listed the states it had heard of would break on a state a later build learned - but
 * that rule is about *reading* a word the server owns. This is a *request*: the contract declares the enum, the API
 * validates against it, and the control offers exactly those two options, so a third value cannot be chosen - and
 * one that arrived by some other route would be refused by the server rather than rendered.
 *
 * **There is no idempotency key, and there is nothing to put one on.** Recording a member is idempotent on
 * `(groupId, userId)`: the pair is the table's key, so a retry that lost its response collides with the row the first
 * attempt wrote, and a second request for the same person is a `200` rather than a second member (P8, ADR 0035).
 */

export type MemberRole = 'admin' | 'member';

export type MemberFormValues = {
  readonly userId: string;
  readonly displayName: string;
  readonly role: MemberRole;
};

export type FieldName = 'userId' | 'displayName' | 'role';

export type Refusal = {
  /** Which control to put the message under, or the form as a whole. */
  readonly field: FieldName | 'form';
  readonly code: string;
  readonly params?: Readonly<Record<string, string | number>>;
};

/** The contract's `displayName` maxLength, refused here for the same reason the API refuses it: not truncated. */
const NAME_MAX_LENGTH = 120;

/**
 * A uuid, as the server reads one.
 *
 * Deliberately loose - no version check, no variant check - because it is not this client's job to be stricter than
 * the API: an id the server accepts must not be refused by a regex on the way. What it catches is the ordinary
 * mistake, which is typing a name into the field that wants an identifier.
 */
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const emptyForm = (): MemberFormValues => ({ userId: '', displayName: '', role: 'member' });

/** Everything that can be decided without asking the server. */
export function localRefusals(values: MemberFormValues): readonly Refusal[] {
  if (!UUID.test(values.userId.trim())) {
    return [{ field: 'userId', code: 'api.request_incomplete' }];
  }
  if (values.displayName.trim() === '') {
    return [{ field: 'displayName', code: 'api.request_incomplete' }];
  }
  if (values.displayName.trim().length > NAME_MAX_LENGTH) {
    // The server's code, so a name refused at either end reads the same: it is the same rule.
    return [{ field: 'displayName', code: 'api.member_name_too_long' }];
  }

  return [];
}

/** The values, as the request the contract describes - or the refusal that stops the request being sent. */
export function addMemberBody(values: MemberFormValues): Result<NewMember> {
  const refusals = localRefusals(values);

  if (refusals.length > 0) {
    return refuse(refusals[0]!.code);
  }

  return ok({
    userId: values.userId.trim(),
    displayName: values.displayName.trim(),
    // Spoken rather than defaulted: the contract's absent `role` means `member`, and this form always has a choice
    // made - the select starts on `member` - so a request that does not say would be a field the member cannot see.
    role: values.role,
  });
}

/**
 * A server refusal, as fields to mark.
 *
 * Every code the endpoint can answer is here, and each is mapped to the control that can fix it - the skill's eighth
 * priority applied to a server that answers with codes rather than prose (RULE B20). A code with no obvious field is
 * shown on the form: it is still the server's answer, and hiding it leaves a member looking at a button that did
 * nothing.
 */
export function refusalsOf(fault: unknown): readonly Refusal[] {
  const problem = problemOf(fault);

  switch (problem.code) {
    case 'api.member_name_too_long':
      return [{ field: 'displayName', code: problem.code, params: problem.params }];

    default:
      // `api.request_incomplete` lands here along with anything this client has not heard of. It covers four missing
      // or malformed fields and names the one it could not read in its `detail` - prose rather than a parameter, so
      // there is nothing to put under a control, and the form-level message is the honest place for it: the request
      // was understood and one part of it was wrong.
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
