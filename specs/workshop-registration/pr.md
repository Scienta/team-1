## Workshop registration with capacity (`workshop-registration`)

Feature request: `specs/workshop-registration/request.md` · Our spec: `specs/workshop-registration/spec.md`

### What changed

- `POST /workshops/{id}/registrations` and `GET /workshops/{id}/registrations` (`WorkshopController`,
  `WorkshopService`, `RegistrationDao`). Only accepted `WORKSHOP` talks take registrations; 20 seats; one
  registration per email per workshop, ignoring case and surrounding whitespace; the list is in registration order
  and stays after a withdrawal.
- Concurrency: each registration locks the talk row (`TalkDao.lockForUpdate`), then checks status, email and count
  and inserts conditionally (`… WHERE COUNT(*) < 20`), all on one connection. A unique index on
  `(talk_id, lower(btrim(email, …)))` is the backstop for duplicates (`DuplicateKeyException` → 409). Acceptance's
  status changes and withdraw lock the same row, so a registration can't interleave with a withdrawal.
- `schema.sql`: one new table, `workshop_registration`, and its unique index, both `IF NOT EXISTS`, appended at the
  bottom. Existing tables and data are untouched.
- `ConferenceService.readObject` is package-visible (one line) so the new service can reuse it. No other change to
  existing code or responses; no existing test changed.
- `openapi.yaml`: the two operations and the `RegistrationRequest`/`Registration` schemas.
- Black box: `examples/workshop-registration.hurl` and `black-box-tests/src/suites/workshop-registration.test.ts`
  (22 tests), with `decisions.md` and the README updated.

### Rules and decisions, and the tests that prove them

All in `WorkshopRegistrationTest`:

| Id | Rule or decision | Test |
|---|---|---|
| R1 | register with name and email → 201 with the registration | `r1_registeringReturns201WithTheRegistration`, `r1_registrationIdsAreUniqueAcrossWorkshops` |
| R2, D2 | only workshops; a talk of another format → 404 | `r2d2_aTalkThatIsNotAWorkshopIs404` |
| R3, D8 | only accepted workshops; otherwise 409, nothing stored | `r3d8_aWorkshopThatIsNotAcceptedIs409AndStoresNothing`, `r3_aWaitlistedWorkshopTakesRegistrationsOnceAccepted` |
| R4, D15 | registrations stay after a withdrawal; the list is always visible | `r4d15_registrationsStayWhenTheWorkshopIsWithdrawn`, `d15_listingAWorkshopThatIsNotAcceptedIs200` |
| R5, D10 | 20 seats, never more, also in a rush | `r5d10_theTwentyFirstRegistrationIs409`, `r5_aRushNeverGetsMoreThanTwentySeats` (60 parallel: 20×201, 40×409), `r5_rushesOnTwoWorkshopsGetTwentyEach` |
| R6, D9, D12 | one per email per workshop, ignoring case and surrounding whitespace → 409 | `r6d9_theSameEmailInAnyCaseIs409AndTheFirstKeepsItsPlace`, `d9d12_whitespaceOtherThanSpacesAroundTheEmailIsTheSamePerson`, `d12_theUniqueIndexAlsoIgnoresCaseAndSurroundingWhitespace`, `r6_parallelDuplicatesGetOneSeat` |
| R7 | one person, several workshops | `r7_theSamePersonCanRegisterForSeveralWorkshops` |
| R8, D13 | email and name returned as typed | `r8d12d13_emailAndNameAreReturnedAsTyped` |
| R9, D16 | list in registration order, only this workshop's | `r9d16_theListIsInRegistrationOrder`, `r9_theListOnlyHasThisWorkshopsRegistrations` |
| D1, D3–D7 | unknown talk 404; bad id, body, name, email → 400 | `d1_…`, `d3_…`, `d4_…`, `d5_…`, `d6_…`, `d7_…` |
| D11, D14, D17–D20 | duplicate on a full workshop, extra fields, own speakers, check order, no cancel (405), full stays 409 after withdraw | `d11_…`, `d14_…`, `d17_…`, `d18_pathBeatsBodyBeatsState`, `d19_thereIsNoCancelling`, `d20_…` |
| Concurrency | a registration racing a withdrawal is either stored or 409 | `concurrency_aRegistrationRacingAWithdrawalIsEitherStoredOr409` |

The tests catch a broken lock: with the talk row lock removed, `r5_rushesOnTwoWorkshopsGetTwentyEach` let 21 in.

`./mvnw package`: 116 tests, all green, none skipped (31 new). Black box: `examples/workshop-registration.hurl`
and the Vitest suite green against a local jar; not yet run against TEAM_URL.

### Review

Independent reviewer (fresh context, read-only): verdict "ready", no blockers. Findings and what we did
(`specs/workshop-registration/review.md`):

| # | Finding | Verdict | What we did, or why not |
|---|---|---|---|
| 1 | Postgres `trim()` strips only spaces, so a tab-padded duplicate email got a second registration | Agree | Check and unique index strip all whitespace Java's `trim()` strips (`RegistrationDao.normalised`); index renamed (the old one never reached `main`); two new tests; D12 reworded |
| 2 | The Hurl example never sent the email in a different case | Agree | Added a different-case duplicate expecting 409 |
| 3 | The spec header still said acceptance isn't on `main` | Agree | Status and Depends lines updated |

No findings rejected.

### Open questions for the product owner

- Not a workshop → 404 (D2): answered, PO confirmed.
- Workshop not accepted → 409 (D8): answered, PO confirmed.
- Same email twice → 409 (D9): answered, PO confirmed.
- List visible whatever the status (D15): answered, PO confirmed.

### Before claiming (after merge, a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] `examples/workshop-registration.hurl` green against TEAM_URL
- [ ] no 5xx in `.github/fagdag/logs.sh`
- [ ] harness: every claimed feature still all OK
- [ ] a person claims; claim-log row added to `REVIEW.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
