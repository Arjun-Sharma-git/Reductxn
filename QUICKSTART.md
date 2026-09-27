# QUICKSTART - running reductxn on a new machine

From nothing to a group whose books close by themselves. Every command below was run on a clean machine, in this
order, against an empty database - including the five failures in the last section, which are real and were found by
writing this file.

## 1. What you need

| Thing | Version | Why this one |
|---|---|---|
| Java | **25** (Temurin) - `sdk install java 25.0.4-tem` | The build's toolchain is `JavaLanguageVersion.of(25)`. Gradle must be >= 9.1 to *run* on 25, which is what `gradle/wrapper` pins (9.7.1). |
| Node | **24** (`engines` says >= 22) | What CI uses. |
| pnpm | **12.3.4**, from `packageManager` in `apps/web/package.json` | Run `corepack enable`; the version comes from the file, so you and CI cannot disagree about which pnpm runs the gates. |
| Docker | any recent | The local stack *and* the Java tests: every module's integration tests run against a real Postgres through Testcontainers, deliberately - H2 would accept a dialect-adjacent version of the SQL and prove the opposite of the thing under test. |

Gradle itself is not needed: the wrapper (jar included) is committed.

## 2. Bring up the database

```bash
docker compose -f infra/docker/compose.yaml up -d postgres pgbouncer
```

**Only these two services are needed**, and the second is not optional:

- The application connects through **PgBouncer on 6432** (the default `spring.datasource.url`), and Flyway connects
  straight to **Postgres on 5432** - DDL cannot go through a transaction pooler.
- `redis`, `redpanda` and `minio` are in the same compose file for the target architecture. Nothing in this build
  talks to them yet.
- Credentials, all local-only: user `reductxn_app`, password `reductxn_local_only`, database `reductxn`.

Wait for `postgres` to report healthy (`docker compose -f infra/docker/compose.yaml ps`).

## 3. Run the two backend deployables

Two commands, two shells. **Both need `REDUCTXN_TAX_JURISDICTION`** - required with no default, because a
deployment that has not been told its jurisdiction would resolve no tax rule, and no tax looks exactly like low tax.

```bash
REDUCTXN_TAX_JURISDICTION=IN ./gradlew :apps:api:bootRun            # HTTP on :8080
REDUCTXN_TAX_JURISDICTION=IN ./gradlew :workers:settlement:bootRun  # no HTTP surface; the settlement cadences
```

On the first start, Flyway applies each owner's migrations - the API's `1xx` plus the shared `libs/*` ranges
(`2xx`, `3xx`, `4xx`), and the worker's `5xx` - and each deployable logs `Started ...`. The ranges are RULE B17;
the per-table ownership table is MASTER-PLAN section 5.5.

**Run both, and expect the API to be useless alone.** The API's obligation read joins the worker's tables
(`settlement_run_obligations`, `settlement_runs` - ADR 0036's one definition of "open"), so with only the API up,
`/groups/{id}/balances`, the netting preview, the obligations list and `/shared/{code}` answer `500` with
`relation "settlement_run_obligations" does not exist`. That is a missing deployable, not a permissions bug.

Optional, for cross-currency work - without a feed, every cross-currency request is refused with
`fx.rate_unavailable`, which is fail-closed rather than broken:

```bash
REDUCTXN_RATES_FEED=https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml \
REDUCTXN_TAX_JURISDICTION=IN ./gradlew :apps:api:bootRun
```

The worker also runs as one-shot commands - `--args='--reductxn.command=open --reductxn.group=<uuid>'` and friends.
`workers/settlement/README.md` lists them all, with their exit codes (`0` done, `1` refused by the domain, `2` the
invocation was wrong).

## 4. Run the web client

```bash
cd apps/web
pnpm install --frozen-lockfile
pnpm dev          # http://localhost:5173, proxying /v1 to http://localhost:8080
```

The proxy is the point: the client has no environment-specific base URL and no CORS, because a deployment is one
origin with a reverse proxy in front of both (`apps/web/vite.config.ts` says so). Point it elsewhere with
`REDUCTXN_API_URL=http://localhost:9090 pnpm dev`.

## 5. The first five minutes in the product

1. Open <http://localhost:5173> and **sign in with any phone number you like**.
2. **The code is not sent anywhere - it is printed to the API's log**, at `WARN`:

   ```
   WARN ... no SMS provider is configured, so this code is going to the log: 630678 for +919800000001
   ```

   That is a stand-in for an SMS gateway, worked around rather than hidden (ADR 0042): there is no provider in this
   build, so "delivering" a code means writing it where an operator can read it.
3. On `/`, create a group (a name and a currency). You are its first member, recorded as `admin`.
4. The group's navigation: **Members** (invite somebody, or make a share link a non-member can read), **Activity**,
   **Commitments**, **Balances**, **Netting preview**, **Schedule**, **Plans** (close the books, read a run, answer
   it), and **Tax**.
5. Record a couple of commitments between two members, then look at **Balances** and the **Netting preview**: they
   are the same computation served twice (ADR 0039).
6. On **Schedule**, state when the books close. The worker then opens cycles by itself, computes a plan for each and
   drives it at the rail - a mock rail by default (`REDUCTXN_RAIL=mock`).

## 6. The gates

Exactly what `.github/workflows/ci.yml` runs, in two jobs - no CI-only flags and no test selection (ADR 0031):

```bash
./gradlew clean build                    # every module + the ArchUnit rules + the coverage checks; needs Docker

cd apps/web
pnpm install --frozen-lockfile
pnpm codegen && git diff --exit-code -- src/shared/api/schema.d.ts   # RULE C3: the generated client is committed
pnpm check                              # types, lint, catalogs, unit tests, a build
pnpm exec playwright install --with-deps chromium
pnpm e2e                                # builds the artefact and drives it in Chromium
```

`pnpm typecheck`, `pnpm lint`, `pnpm check:catalogs` and `pnpm test` are the pieces of `pnpm check`, for when you
want one of them rather than all of it.

## 7. Five things that will bite you

All five were hit while validating this file, in this order, on an empty database.

1. **PgBouncer must be up, and its port mapping is not obvious.** The image starts PgBouncer on `5432` *inside* its
   container while the API dials `6432` on the host, so the mapping is `6432:5432`. With the `6432:6432` mapping
   this file used to have, connections were accepted by Docker and reset with nothing listening - the API started,
   and answered `500` to every database-backed request.
2. **PgBouncer's auth type must be `scram-sha-256`.** Postgres 16 stores the password as a SCRAM verifier and the
   image's own default is `md5`, which fails on the *server* half of the connection with
   `server login failed: wrong password type`. Both settings are in `infra/docker/compose.yaml`, with the reasoning
   beside them.
3. **The two deployables share one `flyway_schema_history` and cannot see each other's migrations.** Whichever
   starts second saw `Detected applied migration not resolved locally: 501, ...` and refused to start. Both now set
   `spring.flyway.validate-on-migrate=false` and `spring.flyway.out-of-order=true`; what that costs (no checksum
   check at startup) and the alternatives are written out in `apps/api/src/main/resources/application.properties`.
   `ignore-migration-patterns=*:missing` is the documented answer to that message and had **no effect** here, which
   is why it is not in the file.
4. **A fresh database is `down -v`.** `docker compose -f infra/docker/compose.yaml down -v` removes the volume;
   `down` alone keeps your data - which is how the smoke test behind this file survived a restart.
5. **Tax rules are reference data with no write operation.** A currency with no rule in force cannot be netted: the
   API answers `tax.rule_missing`, and the tax screen shows only what is in the table. For local play, insert a rule
   (the columns are in `libs/netting`'s `V201__tax_rules.sql`) or stay single-currency.

Ports, for reference: `8080` API, `5173` web dev server, `4173` the built artefact the browser suite drives,
`5432` Postgres, `6432` PgBouncer.

The compose file also mounts `./initdb` into Postgres's entrypoint. That directory is not in git; Docker creates it
empty, so it does nothing today - it is there for seed SQL or roles a deployment grows to need.

## 8. Where to read next

- `docs/MASTER-PLAN.md` - the architecture, the module graph, and the RULEs the build enforces. Start here.
- `docs/adr/` - why each decision is the way it is, oldest first. The recent ones (`0054`-`0057`) cover who may read
  a group's books, share links, who may hand a code out, and when a cycle is due.
- `apps/api/README.md`, `apps/web/README.md`, `workers/settlement/README.md` - each deployable's boundaries, public
  surface and known gaps.
- `contracts/openapi/reductxn-api.yaml` - the API's source of truth. The client's types are generated from it, and
  RULE C3 fails the build if the committed client does not match.
