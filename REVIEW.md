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
- workshop-registration (`feat/workshop-registration`): every rule and decision row in
  `specs/workshop-registration/spec.md` has a named test in `WorkshopRegistrationTest` (29 tests,
  incl. a 60-request rush, parallel duplicate emails and registrations racing a withdrawal). Mutation
  check on the concurrency tests: with the talk row lock removed from `WorkshopService.register`,
  `r5_rushesOnTwoWorkshopsGetTwentyEach` failed with 21 registrations; restored. Also checked with
  `examples/workshop-registration.hurl` (75 requests) and the Vitest suite
  `black-box-tests/src/suites/workshop-registration.test.ts` (22 tests), both green against a local jar
  on a Compose database that already held data. Not yet checked against the deployed service or the harness.
  An independent review agent then found that a tab or line break around an email slipped past the
  duplicate check (Postgres `trim()` strips only spaces), against D9's "surrounding whitespace ignored".
  We fixed it with a test that failed first; triage of all three findings is in `specs/workshop-registration/review.md`.

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| speakers | claimed (base, tick 0) | 0 | Shipped base app; all harness checks OK; `examples/speakers.hurl` green on TEAM_URL |
| talks | claimed (base, tick 0) | 0 | Same; `examples/talks.hurl` green |
| ratings | claimed (base, tick 0) | 0 | Same; `examples/ratings.hurl` green |
| co-speakers | claimed | 67 | Implementation `6319fc6` with `CoSpeakersTest` (6 tests, incl. concurrent adds) and `examples/co-speakers.hurl`; the harness's checks hadn't yet run against it. *(Claimer: add what else you checked.)* Checks 1 and 2 were FAULTY from tick 68, see section 4. |
| acceptance | claimed | 140 | PR #5 merged as `fb2e37f`, `/health` on that SHA; `./mvnw package` 85 tests green; `examples/*.hurl` green on TEAM_URL (84 requests); `black-box` Vitest workflow green on the PR; no 5xx in the logs since the deploy; all other claimed checks OK. Spec PO confirmed (4 answers). Checks 3 and 5 FAULTY from tick 141/142, see section 4. |
| workshop-registration | claimed | 161 | PR #6 merged as `3200676`, deploy green (tests gate it), `/health` on that SHA; `./mvnw package` 116 tests green (31 for this feature, each citing its rule; a 60-request rush gives exactly 20×201, and removing the row lock makes a rush test fail with 21); independent reviewer: no blockers, 3 nits fixed (`specs/workshop-registration/review.md`); `examples/workshop-registration.hurl` green on TEAM_URL (77 requests); no 5xx in the logs since the deploy; the harness's own registrations got 201/409/200 from the new version; all other claimed checks OK at tick 156. Spec PO confirmed (4 answers). |

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

**acceptance, ticks 141–142: repeating a final decision.** Claimed at tick 140; checks 3 and 5 were
FAULTY on `PUT /talks/{id}/status`. check-3 accepted a talk and then accepted it again; check-5
rejected a talk, was refused twice when accepting it (409, fine), then rejected it again. We answered
both repeats with 200 ("idempotent", decision D7). The harness wants them refused: "once a talk is
accepted or rejected, the committee's decision is final". Now 409; waitlisting a waitlisted talk
stays 200 (D6).

Why our review missed it: D7 came from a product owner answer (relayed: "idempotent"), so every
layer (spec, `AcceptanceTest`, `examples/acceptance.hurl`, the Vitest suite) pinned the 200, and the
reviewer judged against the spec. The feature request's own words said "final". Lesson: when a PO
answer loosens a rule the request states, keep the request's wording, or flag it as the riskiest
decision before the claim.

**workshop-registration, tick 162: a talk that isn't a workshop.** Claimed at tick 161; checks 1, 3 and
4 OK on the first run, check-2 FAULTY on `POST /workshops/{id}/registrations`. It rejected a workshop
(our 409, fine), then accepted a *presentation* and registered for it. We answered 404 ("workshop not
found", decision D2); the harness wants something else. Now 409, the other option we had put to the
product owner ("this talk doesn't take registrations"), deployed as `500faa6`. The check then got one
request further and failed at tick 174 on the next one: listing that presentation's registrations,
where we still answered 404. Now 200 with an empty list for any existing talk; only an unknown talk
is 404.

Why our review missed it: the same pattern as acceptance. D2 was a PO-confirmed answer, so the spec,
`WorkshopRegistrationTest`, the Hurl example and the Vitest suite all pinned 404, and the reviewer
judged against the spec. The status code was a coin toss between two readings, and the PO picked the
one the harness doesn't use. Seen before the claim: the harness's own traffic in our logs showed it
registering for talks before the claim, but only on the old version (404 from a missing route), so
nothing told us which code it wanted.

**acceptance, ticks 162–163: waitlisting twice.** After the D7 fix (`297f7d6`) check-3 was OK for ten
ticks, then FAULTY again: it waitlisted a talk and waitlisted it again, and we answered the repeat with
200 (D6, also "idempotent" from the PO). check-3 picks the repeated decision at random, so it only
failed when it drew `WAITLISTED`. Now any repeat of the same decision is 409. Lesson: an intermittent
FAULTY right after a fix is a sibling case of the same rule, not flakiness; we should have dropped D6
together with D7.

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
