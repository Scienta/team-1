# Team-1 agent rules

Root `CLAUDE.md` only imports organisers' `AGENTS.md`. Follow that deploy contract. These rules are ours.

## Source of truth

- Harness specs under `workflow/specs/` (refresh from harness when a drip releases).
- A feature request is not a full spec: we decide status codes, edge cases, and error bodies, then write them down in `workflow/specs/<slug>-decisions.md` before claiming.
- Never tell anyone a feature is done without checkable evidence. Agents cannot claim features; a person clicks claim on the harness.

## Loop: spec → implement → review → evidence → person claims

1. Fetch/refresh `$H/specs/<slug>/spec.md` into `workflow/specs/`.
2. Turn the feature request into our decisions (status codes, errors, trailing slashes, persistence).
3. Implement + unit tests (`./mvnw package`) and Hurl under `examples/<slug>.hurl`.
4. Run `examples/run.sh $TEAM_URL` and hit feedback/timeline for failing checks.
5. Human reviews against `workflow/review-checklist.md`, then claims.

## Hard constraints

- Do not commit `.fagdag/harness-token` or print tokens.
- Do not touch organiser secrets/vars or anything outside our service/DB.
- Prefer `SELECT … FOR UPDATE` / conditional writes over advisory locks; use `SET LOCAL` inside transactions.
- Pool size is 5 — avoid holding connections in long work.
- Add/keep request logging (method, path, status); harness traffic barely shows otherwise.

## Evidence before claim

Minimum: green Hurl against `https://fagsem-h2026-team-1.scienta.cloud`, `./mvnw package` green, and no FAULTY rows for that feature on `/feedback`. Log the claim in `REVIEW.md` §3.
