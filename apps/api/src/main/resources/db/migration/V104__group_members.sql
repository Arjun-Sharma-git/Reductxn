-- A group's members: who can be named.
--
-- Owned by apps/api (MASTER-PLAN section 5.5), which is why this file lives beside the obligations migration and
-- not in platform/persistence - that module runs migrations, it does not own tables.
--
-- `V104` continues apps/api's range: Flyway keeps one version sequence per schema whichever locations it reads,
-- so the 1xx range namespaces this module's files (RULE B17, and the note in V101__obligations.sql).
--
-- **Why this table did not exist until now, and what it fixes.** `GET /groups/{groupId}/members` has been in the
-- contract since the obligations slice, and the client has called it ever since - three screens name people (the
-- obligations list and its filters, the recording form, and a run's answers) and fall back to showing a uuid when
-- the catalogue is empty. Nothing implemented the read, so in development it was always empty, and no test could
-- see it: the client's tests stub the API, and apps/api had never asked for members. A contract promise with
-- nothing behind it is a bug that every suite agrees is fine (ADR 0035).
--
-- **There is no `groups` table yet, and this file deliberately does not create one.** A group in this build is an
-- identifier that rows agree on; nothing about a group is a property of the group, so a `groups` row would hold an
-- id, a timestamp and a name nobody has. It arrives with the thing that needs it - the schedule and policy that
-- decide when a cycle is due (section 5 of the plan) - and until then a foreign key to it would force every
-- membership write to invent a group name. `group_id` is therefore a plain uuid, exactly as `obligations` writes
-- its own.

CREATE TABLE group_members (
    group_id     uuid        NOT NULL,
    user_id      uuid        NOT NULL,

    -- What the group calls them. Not a lookup: there is no `users` table in this build, so the name the group
    -- recorded is the name, and a member who is not in the group is refused by the write that names them rather
    -- than by a directory.
    display_name text        NOT NULL,

    -- Recorded, not enforced: no code consults a role today. The CHECK is the vocabulary (the same reason the
    -- worker names all eight run states), so a role this build has never heard of cannot be stored and then read
    -- back by a screen that would have to guess what it means.
    role         text        NOT NULL,

    joined_at    timestamptz NOT NULL,

    PRIMARY KEY (group_id, user_id),
    CONSTRAINT member_roles_are_known  CHECK (role IN ('admin', 'member')),
    CONSTRAINT member_names_are_not_empty CHECK (length(btrim(display_name)) > 0)
);

-- The index the members read is designed around: `WHERE group_id = ? ORDER BY joined_at, user_id`. The primary key
-- is `(group_id, user_id)`, which serves the lookups (an idempotent add, and any future "is this person in the
-- group") and not this ordering, so the read would sort the group on every page without it.
CREATE INDEX group_members_by_group ON group_members (group_id, joined_at, user_id);
