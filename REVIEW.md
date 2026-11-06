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
4. Review/extend `black-box-tests/` (Vitest) whenever the HTTP API changes — rule in `.claude/rules/black-box-api.md`
5. Human review with `workflow/review-checklist.md`
6. Deploy via push to `main`, verify feedback/logs, then a person claims

Root `CLAUDE.md` only imports organisers' `AGENTS.md`; team rules live under `.claude/rules/` (including black-box API coverage).

## 2. How we reviewed agent output

- Humans run `workflow/review-checklist.md` against the harness request plus our decisions file
- Automated gates: `./mvnw package` (Testcontainers), `examples/run.sh` against the deployed URL,
  Vitest black-box on PRs (`.github/workflows/black-box.yml`), harness `/feedback` and timeline for FAULTY checks
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
- Added a separate Vitest black-box suite (`black-box-tests/`) for deep HTTP coverage of health,
  speakers, talks, ratings, and co-speakers (including the harness-aligned 400/404 co-speaker edges).
  Hurl remains smoke; Vitest is the deep gate. Documented in root README and `black-box-tests/README.md`.
  Prefer `CFP_BASE_URL` so Cursor's `BASE_URL=/` does not silently retarget tests.

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| speakers | claimed (base, tick 0) | 0 | Shipped base app; all harness checks OK; `examples/speakers.hurl` green on TEAM_URL |
| talks | claimed (base, tick 0) | 0 | Same; `examples/talks.hurl` green |
| ratings | claimed (base, tick 0) | 0 | Same; `examples/ratings.hurl` green |
| co-speakers | claimed | 67 | Implementation `6319fc6` with `CoSpeakersTest` (6 tests, incl. concurrent adds) and `examples/co-speakers.hurl`; the harness's checks hadn't yet run against it. *(Claimer: add what else you checked.)* Checks 1 and 2 were FAULTY from tick 68, see section 4. |

Score at workflow setup (tick ~2): total 12, all base checks OK, service not down.

## 4. What the harness caught that we didn't

Nothing on the base features (all checks OK).

**co-speakers, ticks 68–75: two status codes.** Claimed at tick 67; checks 1 and 2 were FAULTY from
the next tick on, and the score bled (−10.67 by tick 75). The transcripts named the request each
check stopped on:

- check-1: removing a co-speaker twice. We answered the second `DELETE` with 200 ("changes
  nothing"); the harness wants an error. Now 404.
- check-2: adding the talk's main speaker as a co-speaker. We answered 409; the harness wants
  something else. Now 400 (the harness's choice isn't published: 400 is our best guess from the base
  features, where it accepted 400 for invalid input and 409 for limits).

Why our review missed it: the feature request gives no status codes, so we picked them ourselves and
then pinned our picks in `CoSpeakersTest` and the Hurl example, which then could only confirm our own
guesses. And the claim came before any harness traffic had been checked against the implementation:
the request logs already showed the harness exercising both cases before the claim.

## 5. Reusable artifacts

- `.claude/rules/black-box-api.md` — on any API change, review/extend Vitest black-box coverage
- `black-box-tests/` — Vitest HTTP suite + `decisions.md` + PR workflow `black-box.yml`
- `workflow/spec-to-claim.md` — end-to-end process
- `workflow/review-checklist.md` — review rubric
- `workflow/templates/decisions.md` — turn a feature request into a real spec
- `workflow/specs/` — cached harness requests for offline/agent use

## 6. One thing we'd do differently

Stand up black-box Vitest (and the PR workflow) earlier, before the next drip claim — Hurl alone is
too thin, and pinning our own status-code guesses only in Java/Hurl made the co-speakers FAULTY
checks a surprise. Also: add request logging in the first deploy so harness traffic shows in Cockpit
before FAULTY timelines are the only signal.
