# Black-box tests

TypeScript + Vitest HTTP suite against a running CFP API. Complements `./mvnw` Testcontainers tests
and `examples/*.hurl` (happy-path smoke). Plan history: [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md).
Agent rule when the API changes: [`.claude/rules/black-box-api.md`](../.claude/rules/black-box-api.md).

## What it covers

| Suite | Focus |
|-------|--------|
| `health` | Deploy `/health` 200 + version |
| `speakers` | Register/read, bio, email uniqueness, trailing slash, concurrency |
| `talks` | Formats/durations/levels, timestamps, max 3 talks, listing, concurrency |
| `ratings` | Scores, replace, rounding, `sort=rating` ties, unrated, concurrency |
| `co-speakers` | Order, idempotent add, max 2, main-as-co 400, remove-missing 404, quota |
| `acceptance` | Full state table, 2 distinct reviewers to accept, main-speaker-only withdraw 403, D1-D13 codes, idempotent repeats, concurrent decisions |
| `workshop-registration` | Accepted workshops only, 404 for non-workshops, 20 seats under a parallel rush, one per email (case/space-insensitive, also in parallel), list order, withdrawn keeps its list, D1-D19 codes |
| `scheduling` | Programme put/get/delete, venue times, ACCEPTED-only, workshop→S4, occupancy/once, speaker clashes, R7 leave-in-place, concurrent cell/slot races |
| `programme-export` | `GET /programme` JSON and `/programme.csv` (RFC 4180, `\n`, quoting, `; ` join), order, current names, empty programme, Accept handling, 405s, exports during parallel edits |

Open status/edge choices we pin in tests: [`decisions.md`](./decisions.md).

## Prerequisites (local)

1. Postgres via Compose (scripts do **not** start it):

   ```bash
   docker compose up -d
   ```

2. Node `>=20`, and the Java version in `/.java-version` (25) for building the jar.

## Commands

```bash
cd black-box-tests
npm ci

# Starts jar (builds if needed), waits for /health, runs Vitest, stops the jar it started
npm run test:local

# App already up (default http://localhost:8080)
npm test

# CI entrypoint (workflow owns Postgres + jar)
npm run test:ci
```

Point at another origin with **`CFP_BASE_URL`** (not bare `BASE_URL` — some tools set `BASE_URL=/`).

From repo root: `npm --prefix black-box-tests run test:local`.

## CI

Workflow: [`.github/workflows/black-box.yml`](../.github/workflows/black-box.yml) on `pull_request` and
`workflow_dispatch`. Job-local Postgres + jar; not wired into `deploy.yml`. Mark the **black-box**
check required in branch protection if it should gate merges.

## Design notes

- Re-runnable via unique emails/ids (no DB truncate). Local Compose DB will grow.
- Factories + optional `seedBaseline()` for read-only cases; prefer helpers over duplicated fetch/assert.
- Vitest runs **one suite file at a time** (`maxWorkers: 1`) so concurrent in-test cases don’t starve the 5-connection pool.
- On API change: add or adjust suites here and update `decisions.md` when needed.
