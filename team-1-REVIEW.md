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

From harness request to claim we run `/feature <slug>` in Claude Code (`.claude/skills/feature/SKILL.md`; the
process for people, with a diagram, is `workflow/feature-process.md`). It stops wherever a person is needed:

0. **Is it free?** No `feat/<slug>` branch on origin, not claimed, not merged (`.claude/rules/feature-workflow.md`).
1. **Branch and mark it taken:** push `feat/<slug>` with the request as released (`specs/<slug>/request.md`). Pushing a
   `feat/` branch doesn't deploy; it's how teammates see the feature is taken.
2. **Spec:** the `spec-writer` agent (`.claude/agents/spec-writer.md`) writes `specs/<slug>/spec.md` from
   `workflow/templates/feature-spec.md`: rules R1.., a decision table for everything the request leaves open, and at
   most five questions for the product owner.
3. **Person:** asks the PO; answers go into the spec as "PO confirmed".
4. **Implement:** the `implementer` agent (`.claude/agents/implementer.md`) works test-first, every test citing a rule
   id, plus `examples/<slug>.hurl` and `black-box-tests/` coverage (`.claude/rules/black-box-api.md`).
5. **Review:** a fresh, read-only `reviewer` agent (`.claude/agents/reviewer.md`) judges only the request, the spec,
   the diff and its own test run.
6. **Triage:** the same implementer agrees or rejects each finding with a reason (`specs/<slug>/review.md`).
7. **PR** from `workflow/templates/pr.md` (`specs/<slug>/pr.md`): rule-to-test table, triage, before-claiming checklist.
8. **Person merges** (deploys; the deploy job `needs` the tests), then the live service is checked (`/health` SHA,
   Hurl and black-box on TEAM_URL, no 5xx in the logs, every claimed check OK) and **a person claims**.

Specs and agents ran this for spring-boot-4, acceptance, workshop-registration, scheduling and ratings-cr; every
`specs/<slug>/` folder holds its request, spec, review triage and PR text. After the game, `workflow/retro.md` and
`workflow/harness-heuristics.md` (PR #14) feed what the harness taught us back into the three agents.

Root `CLAUDE.md` only imports organisers' `AGENTS.md`; team rules live under `.claude/rules/` (including black-box API coverage).

A pull request is ready only when both PR checks are green: `tests` (`.github/workflows/tests.yml`, `./mvnw -B package`) and `black-box` (`.github/workflows/black-box.yml`). The feature skill says the same (`.claude/skills/feature/SKILL.md`). `black-box` also runs on every push to `main`; the deploy job does not wait for it.

## 2. How we reviewed agent output

- A fresh, read-only `reviewer` agent per feature, against the request and our spec (`specs/<slug>/review.md` holds
  each triage). On spring-boot-4 it caught that trailing slashes, which Spring 5 matched on *every* URL, had only been
  restored for `/speakers/**`: `GET /talks/1/` had silently become 404. Fixed on all paths with a test that failed
  first, and the before/after script gained 18 slash requests.
- spring-boot-4 (2.7 → 4.1.2, Java 11 → 25) was proven by an old-vs-new comparison: the same 122 requests against the
  2.7 image (as production) and the 4.x image on fresh databases. When the PO then required error bodies to be
  *byte-identical* (framework-written `message` texts included), 22 recorded 2.7 error bodies became a test
  (`LegacyErrorBodiesTest`) that failed 20/22 first; in the end 3 lines differed: the two `/actuator/info` versions
  and one Jackson parse-error column number (listed for the PO in `specs/spring-boot-4/evidence.md`).
- The deploy's test gate (added with the `/feature` workflow: the deploy job `needs: tests`) caught a broken `main` in
  the last minutes: #12 (scheduling DELETE → 204) and #11 (programme-export, whose concurrency test only allowed
  200/404/409) were each green on their own PR, but together failed `ProgrammeExportTest`, so the deploy of `950d73b`
  was skipped and production kept serving. One-line test fix in #15.
- Performance (`fix/talk-list-performance`, #8): `GET /talks` and `/admin/stats` took 25–35 s on production data
  (N+1 queries, ~6,500 talks), which made the black-box suite time out against TEAM_URL. Now 4–6 queries and under
  0.25 s, responses byte-identical (diffed on a 7,000-talk database). The logs showed the harness only ever lists
  one speaker's talks, so this protected our own pre-claim checks rather than harness checks.
- Automated gates: `./mvnw package` (Testcontainers), `examples/run.sh` against the deployed URL,
  on PRs both `tests` (`.github/workflows/tests.yml`, Java build and tests) and `black-box`
  (`.github/workflows/black-box.yml`) must be green before the PR is ready, harness `/feedback` and timeline for FAULTY checks
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
- Concurrency review of `src/main` (agent, read against the code and the schema's unique indexes): parallel
  `PUT /speakers/{id}` requests moving different speakers to one email got 500, not 409. The check-then-update
  in `ConferenceService.updateSpeaker` raced, and `ux_speaker_email`'s `DuplicateKeyException` went uncaught.
  Reproduced locally (6 parallel PUTs ×5 runs: 1–3 × 500 per run). Fixed by mapping it to 409. The new
  `SpeakersTest.concurrentUpdatesToOneEmailHaveOneWinnerAndNoServerError` failed with the fix removed and passes
  with it. All Java tests and the Vitest suite (111 tests, new parallel-update test included) pass against a JDK 25
  jar on the Compose database. Not yet checked against the deployed service. The same review flagged, without
  reproducing it, that `/health` borrows from the 5-connection pool with no bound on connection wait, so it
  can return 503 under pool contention; left open.
- programme-export (`feat/programme-export`): every rule and decision row in `specs/programme-export/spec.md`
  has a named test in `ProgrammeExportTest` (23 tests, written first and seen failing with 404, incl.
  exports racing parallel scheduling PUT/DELETE). Also `examples/programme-export.hurl` (18 requests) and
  `black-box-tests/src/suites/programme-export.test.ts` (20 tests), both green against a local jar on a
  Compose database that already held data; the whole Vitest suite (152 tests) ran green in a `node:22-alpine`
  container, since Node isn't installed on the dev machine. Not yet checked against the deployed service or the harness.

## 3. Claim log

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| speakers | claimed (base, tick 0) | 0 | Shipped base app; all harness checks OK; `examples/speakers.hurl` green on TEAM_URL |
| talks | claimed (base, tick 0) | 0 | Same; `examples/talks.hurl` green |
| ratings | claimed (base, tick 0) | 0 | Same; `examples/ratings.hurl` green |
| co-speakers | claimed | 67 | Implementation `6319fc6` with `CoSpeakersTest` (6 tests, incl. concurrent adds) and `examples/co-speakers.hurl`; the harness's checks hadn't yet run against it. *(Claimer: add what else you checked.)* Checks 1 and 2 were FAULTY from tick 68, see section 4. |
| acceptance | claimed | 140 | PR #5 merged as `fb2e37f`, `/health` on that SHA; `./mvnw package` 85 tests green; `examples/*.hurl` green on TEAM_URL (84 requests); `black-box` Vitest workflow green on the PR; no 5xx in the logs since the deploy; all other claimed checks OK. Spec PO confirmed (4 answers). Checks 3 and 5 FAULTY from tick 141/142, see section 4. |
| workshop-registration | claimed | 161 | PR #6 merged as `3200676`, deploy green (tests gate it), `/health` on that SHA; `./mvnw package` 116 tests green (31 for this feature, each citing its rule; a 60-request rush gives exactly 20×201, and removing the row lock makes a rush test fail with 21); independent reviewer: no blockers, 3 nits fixed (`specs/workshop-registration/review.md`); `examples/workshop-registration.hurl` green on TEAM_URL (77 requests); no 5xx in the logs since the deploy; the harness's own registrations got 201/409/200 from the new version; all other claimed checks OK at tick 156. Spec PO confirmed (4 answers). |
| spring-boot-4 | claimed | 184 | PR #3 squash-merged as `244682a`, deploy green (tests gate); on TEAM_URL `/health` on that SHA and `/actuator/info` `springBoot 4.1.2`, `springFramework 7.0.10`, `java 25.0.5` read from the runtime; `examples/run.sh` 7 files / 170 requests green; 0 responses with a 5xx status in the logs since the deploy; harness: every claimed check OK at tick 171. Before merge: 153 tests on Java 25, black-box 110/110, two reviewer rounds (`specs/spring-boot-4/review.md`), 122-request old-vs-new diff (`specs/spring-boot-4/evidence.md`). Both checks OK from the claim to the end. |
| scheduling | claimed | 198 | *(Claimer: add the evidence you held.)* Checks FAULTY from tick 199 until the fix `7ee8986` (#12) was live at tick ~213, see section 4. |
| ratings-cr | not claimed | – | Spec (PO confirmed), implementation and tests ready ~15:56 (#13, merged `57a218f`); its deploy was first superseded by #11's and then skipped by the test gate (see section 2), so it wasn't live before the game ended at 16:05. |
| submission-cap-cr | skipped | – | Released tick 180; not started: a concurrency-heavy change that couldn't be specced, built, reviewed and deployed before the end. |
| programme-export | not claimed | – | #11 merged as `950d73b` at ~15:59; same skipped deploy as ratings-cr. |

Score at workflow setup (tick ~2): total 12, all base checks OK, service not down.
Final score (tick 218): **1,309.15** (earned 1,465.05, bled 155.90); 8 features claimed, all checks OK at the end.

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
is 404 (`a2d0e47`). check-2 OK from tick 181 (FAULTY ticks 162–180).

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

**scheduling, ticks 199–203: DELETE body and unknown talk.** Claimed at tick 198; all six checks FAULTY
from the first run. Checks 2–6 failed on `DELETE /programme/{room}/{slot}`. Each check first clears the
cells it will use. A DELETE of an empty cell (our 404) passed; a DELETE that took a talk out (our 200 with
the removed `Entry`, D12) failed the check right there. So check-2 was OK at ticks 199 and 201, when its
cell happened to be empty, and FAULTY at 200 and 202. Check-1 failed on `PUT` with an unknown `talkId`,
where we answered 404 (D4). Changed on `fix/scheduling-harness`: DELETE is 204 with no body, and an
unknown `talkId` is 400 (as an unknown `speakerId` is on `POST /talks`). Both are our reading of the
request; the harness only says what it doesn't want. Merged as `7ee8986` (#12) and live just before tick 213; every scheduling check OK from tick 214 to the end, so both readings were what the harness wanted.

Why our review missed it: D12 and D4 were two of the guesses the spec had flagged for the product owner
(Q2 asked about DELETE in so many words), and the team went ahead without answers. Spec, `ProgrammeTest`,
the Hurl example and the Vitest suite then all pinned the guesses. Same lesson as acceptance and
workshop-registration: an unanswered status-code question is the riskiest line in the spec. For a
DELETE the request gives no response for, 204 is the conventional reading.

## 5. Reusable artifacts

- `.claude/rules/black-box-api.md` — on any API change, review/extend Vitest black-box coverage
- `black-box-tests/` — Vitest HTTP suite + `decisions.md` + `black-box.yml` (pull requests and push to `main`)
- `.github/workflows/tests.yml` — PR-only Java build and tests (`./mvnw -B package`). With `black-box.yml`, both checks must be green before a PR is ready
- `.claude/skills/feature/SKILL.md` — `/feature <slug>`: the whole chain from request to PR, with the person steps
- `.claude/agents/spec-writer.md`, `implementer.md`, `reviewer.md` — the three roles (the reviewer is read-only and
  starts fresh, so it isn't anchored on the implementer's reasoning)
- `workflow/feature-process.md` — the process for people, with the diagram and why each step exists
- `workflow/templates/feature-spec.md`, `workflow/templates/pr.md` — spec (rules, decision table, PO questions) and PR
  (rule-to-test table, triage, before-claiming checklist)
- `.claude/rules/feature-workflow.md` — check `feat/<slug>` on origin before starting; never push `main` without a person
- `workflow/retro.md`, `workflow/harness-heuristics.md` (PR #14) — every post-claim FAULTY check with root cause, and
  H1–H20: what the harness probes and expects, which the agents now check decisions against
- `.github/workflows/deploy.yml` — tests gate the deploy (`needs: tests`)
- `specs/<slug>/` — per feature: request as released, our spec, review triage, PR text, evidence

## 6. One thing we'd do differently

Stand up black-box Vitest (and the PR workflow) earlier, before the next drip claim — Hurl alone is
too thin, and pinning our own status-code guesses only in Java/Hurl made the co-speakers FAULTY
checks a surprise. Also: add request logging in the first deploy so harness traffic shows in Cockpit
before FAULTY timelines are the only signal.
