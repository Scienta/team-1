# GitHub Actions

Workflows for this team's CFP service. Organiser secrets/vars (`SCW_*`, `TEAM_URL`) must not be
changed. Helpers under `.github/fagdag/` (`rollout.sh`, `logs.sh`) are used by these jobs.

| Workflow | File | When | Purpose |
|----------|------|------|---------|
| **deploy** | [`deploy.yml`](deploy.yml) | Push to `main`, or `workflow_dispatch` | Maven tests gate, then build/push image and roll out to Scaleway |
| **tests** | [`tests.yml`](tests.yml) | Pull request opened, updated, or reopened | `./mvnw -B package`: Java build and tests. A PR is ready only when this and **black-box** are both green |
| **black-box** | [`black-box.yml`](black-box.yml) | Pull request, push to `main`, or `workflow_dispatch` | Vitest HTTP suite against a job-local Postgres + jar |
| **logs** | [`logs.yml`](logs.yml) | `workflow_dispatch` only | Print container logs from Scaleway Cockpit |

## deploy

- **Jobs:** `tests` (`./mvnw -B test`) then `deploy` (`needs: tests`).
- Builds the root `Dockerfile` for `linux/amd64` with `GIT_SHA`, pushes `app:<sha>` and `app:latest`,
  runs `.github/fagdag/rollout.sh`, waits for `/health` with the new version.
- On rollout failure, tags roll back to the previous `app:latest` and the job dumps Cockpit logs.
- Concurrency group `deploy` — one rollout at a time; newer pushes wait.
- Not the same as black-box: this gates **production deploy**, not PR merge (unless you also require
  checks in branch protection).

## tests

- Pull requests only (`opened`, `synchronize`, `reopened`). A new push to the same PR cancels the in-progress run.
- `./mvnw -B package` on the runner's Docker (Testcontainers). Produces the jar and runs the Java tests.
- Does not run `black-box-tests/`. That suite is the **black-box** workflow.
- A PR is ready only when **tests** and **black-box** are both green (`.claude/skills/feature/SKILL.md`).

## black-box

- Deep HTTP tests in [`black-box-tests/`](../../black-box-tests/README.md) (TypeScript + Vitest).
- Job starts Postgres 16 (`cfp`/`cfp`/`cfp`), builds the jar, starts it on `:8080`, runs
  `npm run test:ci` with `CFP_BASE_URL=http://localhost:8080`, then stops the jar.
- Also runs on a push to `main`, alongside the deploy build. It does **not** hit the deployed `TEAM_URL`, and `deploy` does not wait for it.
- To block merges, mark the **black-box** check required in branch protection.
- On API changes, extend the suite per [`.claude/rules/black-box-api.md`](../../.claude/rules/black-box-api.md).

## logs

- Manual run from the Actions tab; inputs `tail` (default 200) and optional `since` (RFC 3339).
- Invokes `.github/fagdag/logs.sh` with the committed read-only Cockpit token.
- From a laptop, running that script directly is usually faster than waiting for the workflow.
