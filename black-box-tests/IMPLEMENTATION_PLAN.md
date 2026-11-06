# Black-box tests — implementation plan

TypeScript + Vitest suite in `black-box-tests/**`, re-runnable, helpers over duplication, runnable locally and in GitHub Actions.

Status: **implemented** (all 14 decisions locked; suites green against local app).

## Decisions

| # | Topic | Decision |
|---|--------|----------|
| 1 | Local app lifecycle | **B** — convenience scripts start the jar (and optionally stop it after) |
| 2 | Local Postgres lifecycle | **A** — assume `docker compose` Postgres already running; fail fast if unreachable |
| 3 | GitHub Actions target | **A** — job-local stack (Postgres service + jar in the workflow); not deployed TEAM_URL |
| 4 | Data isolation / re-runnability | **A** — unique emails/ids per test; no truncate/cleanup |
| 5 | Shared-state strategy | **B** — shared seed in `beforeAll` + unique ids where needed |
| 6 | Feature slice | **A** initially; **co-speakers suite added** after base |
| 7 | Depth (happy vs edges) | **C** — exhaustive vs harness base specs (+ our decisions where we have them) |
| 8 | Relationship to `examples/*.hurl` | **A** — keep Hurl as smoke; Vitest is the deep suite |
| 9 | npm script names | **A** — `test` / `test:local` / `test:ci` as proposed |
| 10 | CI workflow placement | **A** — new `.github/workflows/black-box.yml` (PR + `workflow_dispatch`) |
| 11 | Deploy gating | **B** — required PR check; not wired into `deploy.yml` |
| 12 | Node version | **C** — loose pin via `engines` + Actions `setup-node`; no hard major lock beyond that |
| 13 | HTTP helper style | **B** — richer client: retries on connect, path helpers, typed assert helpers |
| 14 | Fixtures style | **B** — factories + shared `beforeAll` seed module for read-only cases |

## Notes from decisions

- **Q1:** Scripts own the app process locally: build/start jar if needed, wait for `/health`, run Vitest, then stop the jar they started (don’t kill an unrelated process on 8080 if we didn’t start it — track PID).
- **Q2:** Docs/scripts remind to `docker compose up -d`; preflight checks DB (or `/health` → `db-unavailable`) rather than starting Compose.
- **Q3:** CI mirrors local black-box: workflow starts Postgres (service container), builds/starts jar, runs Vitest, tears down. No dependency on Scaleway/`TEAM_URL` for this suite.
- **Q4:** Factories mint unique emails/names (UUID); no DB truncate. DB will grow locally over time — acceptable.
- **Q5:** File-level (or suite-level) `beforeAll` can seed shared entities; mutating tests still create their own unique speakers/talks so re-runs don’t collide. Prefer pure seed helpers over a mutable global bag.
- **Q6:** First suites: `health`, `speakers`, `talks`, `ratings`. Co-speakers out of v1.
- **Q7:** Structure tests from `workflow/specs/{speakers,talks,ratings}.md` (and health deploy contract). Where the harness leaves status codes/edges unspecified, encode *our* observed/decided behaviour in `black-box-tests/decisions.md` so tests stay intentional.
- **Q8:** No Hurl→Vitest migration. `examples/run.sh` stays the light smoke path.
- **Q9:** `package.json` scripts: `test` (vitest, requires live app), `test:local` (orchestrate jar + vitest + stop), `test:ci` (vitest for Actions; workflow sets `BASE_URL` and owns jar/Postgres lifecycle).
- **Q10:** Dedicated workflow; keep `deploy.yml` untouched. Triggers: `pull_request` + `workflow_dispatch`.
- **Q11:** Branch protection should require the black-box job (repo setting). Deploy path stays independent.
- **Q12:** `engines.node`: `>=20`; workflow uses `node-version: lts/*`.
- **Q13:** `helpers/http.ts` (fetch + connect retry + BASE_URL) and `helpers/expect.ts` (pure assert helpers). Client holds config only.
- **Q14:** `helpers/factories.ts` for create-*; `helpers/seed.ts` for shared read-only seed used from `beforeAll`.

## Layout

```
black-box-tests/
  IMPLEMENTATION_PLAN.md
  README.md                 # how to run locally + CI notes
  decisions.md              # status codes / edges we assert that harness left open
  package.json
  package-lock.json
  vitest.config.ts
  tsconfig.json
  scripts/
    run-local.mjs           # preflight → build jar if needed → start → wait health → vitest → stop
  src/
    helpers/
      env.ts                # BASE_URL (default http://localhost:8080)
      http.ts               # fetch client, retries on connect, path join
      expect.ts             # expectStatus, expectJson, …
      ids.ts                # uniqueEmail(), uniqueName()
      factories.ts          # createSpeaker, createTalk, rateTalk, …
      seed.ts               # seedBaseline() → { speaker, talk, … } for read-only cases
    suites/
      health.test.ts
      speakers.test.ts
      talks.test.ts
      ratings.test.ts
.github/workflows/black-box.yml
```

## Scripts

| Script | Behaviour |
|--------|-----------|
| `npm test` | `vitest run` against `CFP_BASE_URL` (default `http://localhost:8080`; app must already be up) |
| `npm run test:local` | `scripts/run-local.mjs`: fail if Postgres/health bad → `./mvnw -DskipTests package` if no jar → start jar with PG* for compose defaults → wait `/health` 200 → `vitest run` → stop tracked PID |
| `npm run test:ci` | `vitest run` only; Actions owns Postgres + jar + `CFP_BASE_URL` |

Root convenience (optional in implementation): document `npm --prefix black-box-tests run test:local`.

## Local flow

1. `docker compose up -d` (human; not automated).
2. `cd black-box-tests && npm ci && npm run test:local`
3. Or: app already running → `npm test`

## CI flow (`.github/workflows/black-box.yml`)

1. Checkout; set up Java 11 + Node `lts/*`.
2. Postgres service container (`postgres:16-alpine`, db/user/password `cfp`, port 5432).
3. `./mvnw -DskipTests package`
4. Start `java -jar target/cfp.jar` with `PGHOST=localhost` (etc.); wait for `/health`.
5. `npm ci` + `npm run test:ci` in `black-box-tests/` with `BASE_URL=http://localhost:8080`.
6. Stop jar; job fails if Vitest fails.
7. Reminder in README: mark this check required in branch protection.

## Test design rules

- Exhaustive coverage of base harness specs (speakers, talks, ratings) + deploy `/health`.
- Record unspecified status/edge choices in `decisions.md`; tests assert those deliberately.
- Mutating cases: always unique factories (re-runnable, no truncate).
- Read-only cases: may use `seedBaseline()` from `beforeAll`.
- Prefer pure helpers; no duplicated fetch/assert blobs across suites.
- Do not delete or replace `examples/*.hurl`.

## Implementation order

1. Scaffold `package.json`, Vitest, tsconfig, `helpers/*`, empty suites, README, `decisions.md`.
2. `scripts/run-local.mjs` + verify `test:local` against compose.
3. Fill suites from specs (health → speakers → talks → ratings).
4. Add `.github/workflows/black-box.yml`.
5. Smoke on a PR; ask human to require the check in branch protection.

## Out of scope (v1)

- Co-speakers
- Replacing `./mvnw` Testcontainers tests
- Replacing harness checks
- Wiring into `deploy.yml` / `TEAM_URL`
- DB truncate / dedicated `cfp_bb` database
