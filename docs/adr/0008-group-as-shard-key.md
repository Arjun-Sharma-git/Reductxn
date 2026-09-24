# ADR 0008 - Group as the shard key

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** RULE B17; see `MASTER-PLAN.md` section 3

## Context

Shareable groups of 200 members at a time, millions of users. The workload is almost entirely
group-scoped: obligations, balances, cycles, settlements, reports.

## Decision

`group_id` is the shard key. Group-scoped tables are partitioned by it; groups are the
consistency boundary. Cross-group queries are structurally discouraged and are not part of any
request path. The user/group registry stays unsharded and is cached.

## Consequences

- **Easier:** predictable horizontal scaling; per-group consistency without distributed
  transactions; hot groups are isolatable.
- **Harder:** any genuinely cross-group feature becomes an explicit new design, not a query. A
  single very large group is a hot partition, which is why the 200-member cap exists.

## Alternatives considered

- **Shard by user** - rejected: every group operation would fan out across shards.
- **No sharding, scale vertically** - rejected: leaves no growth path past one writer.
