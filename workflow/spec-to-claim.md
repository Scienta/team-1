# Spec → claim (team-1)

Use this for every drip feature and change request.

## 1. Pull the request

```bash
H=$(cat .fagdag/harness-url) TOKEN=$(cat .fagdag/harness-token)
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/specs/<slug>/spec.md" -o workflow/specs/<slug>.md
```

Person claims the feature on the harness when ready to own it (claim is permanent).

## 2. Write decisions

Create `workflow/specs/<slug>-decisions.md`: status codes, error bodies, trailing-slash policy, uniqueness, ordering, concurrency. Ask the product owner only for business-rule gaps.

## 3. Build

- Schema + DAO + controller changes
- Unit tests in `src/test`
- `examples/<slug>.hurl` (happy path + the edges we decided)

```bash
./mvnw package
examples/run.sh https://fagsem-h2026-team-1.scienta.cloud
```

## 4. Review

Run `workflow/review-checklist.md`. Fix before push if anything fails.

## 5. Deploy + verify

Push to `main`, wait for deploy green, confirm `/health` version = new SHA.

```bash
.github/fagdag/logs.sh --tail 200 --since <ISO>
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/api/teams/team-1/feedback"
# optional: .../feedback/timeline?check=<slug>/<check>
```

## 6. Person claims (if not already) and log

Update `REVIEW.md` §3 with tick, evidence, and outcome. If harness FAULTY after claim, write §4.
