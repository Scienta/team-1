# Black-box tests

TypeScript + Vitest HTTP suite against a running CFP API. Plan: [`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md).

## Prerequisites (local)

1. Postgres via Compose (scripts do **not** start it):

   ```bash
   docker compose up -d
   ```

2. Node `>=20`, Java 11+ for building the jar.

## Commands

```bash
cd black-box-tests
npm ci

# Starts jar (builds if needed), waits for /health, runs Vitest, stops the jar it started
npm run test:local

# App already on CFP_BASE_URL (default http://localhost:8080)
npm test

# CI entrypoint (workflow owns Postgres + jar + CFP_BASE_URL)
npm run test:ci
```

Use `CFP_BASE_URL` (not bare `BASE_URL`) — some environments set `BASE_URL=/`.

From repo root: `npm --prefix black-box-tests run test:local`.

## CI

Workflow: `.github/workflows/black-box.yml` (pull_request + workflow_dispatch).  
Mark that check **required** in branch protection; it is not wired into `deploy.yml`.

## Notes

- Re-runnable via unique emails/ids (no DB truncate). Local Compose DB will grow.
- Deep coverage of base specs; `examples/*.hurl` remains the light smoke path.
