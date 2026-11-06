# Review checklist (agent output)

Reviewer: human (or second agent) against `workflow/specs/<slug>.md` + `<slug>-decisions.md`.

- [ ] Business rules from the harness request are covered
- [ ] Our decisions cover status codes / errors / trailing slashes / ordering
- [ ] Persistence survives restart (DB, not ephemeral disk)
- [ ] No advisory-lock reliance; row locks or conditional writes if needed
- [ ] `./mvnw package` green locally
- [ ] Hurl against deployed `TEAM_URL` green
- [ ] Request logging still shows method/path/status for new routes
- [ ] No secrets in the diff; `.fagdag/harness-token` untouched
- [ ] Deploy contract intact: `/health`, port 8080, `GIT_SHA` version
- [ ] Evidence listed in `REVIEW.md` before anyone claims “done”
