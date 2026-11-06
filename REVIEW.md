# REVIEW

> **Showcase brief (17:00, 4 minutes, hard stop).** You screen-share this repo from the shared
> presenter laptop, in a random order drawn at 17:00. No slides, and no harness screens.
>
> 1. **Our workflow in one minute:** section 1, pointing at the files in `workflow/` and `.claude/`.
> 2. **One story:** a bug our review caught before the harness did, or one the harness caught that
>    we missed, and why. Use the claim log.
> 3. **One artifact we'd reuse**, opened on screen.
>
> Keep this file as a running log from t = 0, so 17:00–17:05 is for tidying, not writing. To be
> eligible to win, every heading below needs some text. The comments under each heading are prompts
> and don't count; replace them with your own words.

## 1. Workflow

From harness request to claim we follow `workflow/spec-to-claim.md`:

1. Refresh the spec into `workflow/specs/<slug>.md`
2. Write status/edge decisions in `workflow/specs/<slug>-decisions.md` (template: `workflow/templates/decisions.md`)
3. Implement + unit tests + `examples/<slug>.hurl`
4. Human review with `workflow/review-checklist.md`
5. Deploy via push to `main`, verify feedback/logs, then a person claims

Agent instructions live in `.claude/rules/team-workflow.md` (root `CLAUDE.md` only imports organisers' `AGENTS.md`).

## 2. How we reviewed agent output

- Humans run `workflow/review-checklist.md` against the harness request plus our decisions file
- Automated gates: `./mvnw package` (Testcontainers), `examples/run.sh` against the deployed URL, harness `/feedback` and timeline for FAULTY checks
- Deploy workflow must stay green (`/health` with new `GIT_SHA`)
- Test review against the base specs, rule by rule, while all harness checks were green: ratings had
  no tests at all (`RatingsTest` is new), and `SpeakersTest`/`TalksTest` now cover their specs; a
  shared `Api` test helper replaces copy-pasted HTTP code. Writing the missing tests first caught
  bugs the harness hadn't flagged:
  - `ratedAtDisplay` depended on the JDK: on Java 20+ the US short format puts a narrow no-break
    space (U+202F) before AM/PM. Production runs Java 11, so the harness never saw it; a local run on
    Java 21 did. Fixed with an explicit `M/d/yy, h:mm a` pattern.
  - SQL injection in the admin search (`?q=' OR '1'='1` returned every speaker). Fixed with a bound
    parameter.
  - `POST /speakers` took any non-blank string as an email. Now checked with `Utils.isValidEmail`;
    before shipping we replayed the harness's 901 logged registrations: every email it had accepted
    still passes.

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| speakers | claimed (base, tick 0) | 0 | Shipped base app; all harness checks OK; `examples/speakers.hurl` green on TEAM_URL |
| talks | claimed (base, tick 0) | 0 | Same; `examples/talks.hurl` green |
| ratings | claimed (base, tick 0) | 0 | Same; `examples/ratings.hurl` green |
| co-speakers | not claimed yet | released (seen at tick 2) | Spec cached in `workflow/specs/co-speakers.md`; implementation not started |

Score at workflow setup (tick ~2): total 12, all base checks OK, service not down.

## 4. What the harness caught that we didn't

Nothing yet on base features (all checks OK after setup). Will log FAULTY post-claim findings here with why review missed them.

## 5. Reusable artifacts

- `.claude/rules/team-workflow.md` — agent loop and hard constraints
- `workflow/spec-to-claim.md` — end-to-end process
- `workflow/review-checklist.md` — review rubric
- `workflow/templates/decisions.md` — turn a feature request into a real spec
- `workflow/specs/` — cached harness requests for offline/agent use

## 6. One thing we'd do differently

Add request logging in the first deploy, before the next drip claim — without it harness traffic is nearly invisible in Cockpit logs, so FAULTY timelines become the only signal.
