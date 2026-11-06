## <Feature name> (`<slug>`)

Feature request: `specs/<slug>/request.md` · Our spec: `specs/<slug>/spec.md`

### What changed

- ...

### Rules and decisions, and the tests that prove them

| Id | Rule or decision | Test |
|---|---|---|
| R1 | | `CoSpeakersTest#...` |

`./mvnw package`: <n> tests, all green. Black box: `examples/<slug>.hurl`.

### Review

Reviewer findings and what we did (`specs/<slug>/review.md`):

| # | Finding | Verdict | What we did, or why not |
|---|---|---|---|

### Open questions for the product owner

- ... (answered / unanswered)

### Before claiming (after merge, a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] `examples/<slug>.hurl` green against TEAM_URL
- [ ] no 5xx in `.github/fagdag/logs.sh`
- [ ] harness: every claimed feature still all OK
- [ ] a person claims; claim-log row added to `team-1-REVIEW.md`
