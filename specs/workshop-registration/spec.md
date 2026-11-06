# Workshop registration with capacity: our spec

Feature request: `specs/workshop-registration/request.md` (harness slug `workshop-registration`, V 2, released at tick 67).
Status: PO confirmed (questions 1–4), ready to implement once acceptance is on main

Depends on the acceptance state machine (`specs/acceptance/spec.md` on `origin/feat/acceptance`, not on `main`
yet). Status values used here are theirs: `SUBMITTED`, `ACCEPTED`, `REJECTED`, `WAITLISTED`, `WITHDRAWN`. A talk
only becomes `ACCEPTED` through `PUT /talks/{id}/status` after two distinct reviewers have rated it.

## Rules (from the feature request)

- R1 An attendee registers for a workshop with `POST /workshops/{id}/registrations` `{"name", "email"}`.
  Both `name` and `email` are required. Success is 201 with the Registration.
- R2 Only talks with format `WORKSHOP` take registrations ("workshop" = talk with format `WORKSHOP`).
- R3 Only workshops whose status is `ACCEPTED` take registrations.
- R4 If a workshop is withdrawn after people registered, the registrations stay: they're still on its list.
- R5 A workshop takes at most **20** registrations, never more, also when registrations arrive in parallel
  ("registration opens to everyone at the same moment, so expect a rush").
- R6 One registration per email per workshop. Emails compare case-insensitively: `ada@example.com` and
  `ADA@Example.com` are the same person. This must also hold for parallel requests.
- R7 The same person (email) can register for several workshops.
- R8 `Registration.email` is returned **as the attendee typed it** (not lowercased).
- R9 `GET /workshops/{id}/registrations` lists the workshop's registrations in the order they registered
  (first registered first).

## API

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/workshops/{id}/registrations` | `{"name": "Ada", "email": "ADA@Example.com"}` | 201, `{"id", "workshopId", "name", "email"}` |
| GET | `/workshops/{id}/registrations` | | 200, array of Registration, registration order; `[]` when none |

`id` in the path is the workshop's talk id. `Registration.id` is a new integer id (unique across all
registrations), `workshopId` the talk id, `name` and `email` exactly as sent.

New or changed fields in existing responses: none. Talk responses don't get a registration count.

## Decisions (where the request is silent)

Conventions (same as acceptance D12 and co-speakers): 404 = the path resource doesn't exist, 400 = the request
is invalid in itself, 409 = a business rule refuses it given the current state. Errors use the existing
`ResponseStatusException` JSON error body.

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | unknown talk id (POST or GET) | 404 | same as `GET /talks/{id}` |
| D2 | talk exists but its format isn't `WORKSHOP` (POST or GET) | 404 | the path names a *workshop*; a presentation is no workshop. **PO confirmed** (question 1) |
| D3 | path id not an integer (`/workshops/abc/registrations`) | 400 | Spring's default type-mismatch answer, as for `/talks/abc` |
| D4 | body missing, empty or not JSON | 400 | Spring's default for an unreadable body |
| D5 | `name` missing, null, not a JSON string, empty or only whitespace | 400 | "required"; same as speaker name (`@NotBlank`). A number is **not** coerced to a string |
| D6 | `email` missing, null, not a JSON string, empty or only whitespace | 400 | "required" |
| D7 | `email` present but not an email (`Utils.isValidEmail` false: no `@`, no `.` after the `@`) | 400 | same check and status as speaker registration |
| D8 | workshop exists but isn't `ACCEPTED` (`SUBMITTED`, `WAITLISTED`, `REJECTED`, `WITHDRAWN`) | 409, nothing stored | R3; the state refuses it and acceptance would make it pass. **PO confirmed** (question 2) |
| D9 | the same email (case-insensitive, surrounding whitespace ignored) registers again for the same workshop | 409, nothing stored; the first registration keeps its name and place | R6; same as a duplicate speaker email (409). **PO confirmed** (question 3) |
| D10 | 21st registration (workshop already has 20) | 409, nothing stored | R5; capacity is a state-dependent rule like the 3-talk limit |
| D11 | duplicate email on a full workshop | 409 | both checks give 409; which one is checked first doesn't matter to the caller |
| D12 | email compare | `lower(trim(email))`; stored and returned untrimmed and in original case | R6 + R8. Trimming only makes the duplicate check stricter |
| D13 | `name` stored as sent (not trimmed) | as sent | "as typed", like speakers; the request doesn't ask for normalising |
| D14 | unknown extra fields in the body (`{"name","email","phone"}`) | ignored | Spring Boot default; same as the other POSTs |
| D15 | GET on a workshop that isn't (or is no longer) `ACCEPTED` | 200 with its list (`[]` if nobody registered) | R4 requires the list of a withdrawn workshop; listing never depends on status. **PO confirmed** (question 4) |
| D16 | registration order | ascending `Registration.id`, which is assigned while the workshop row is locked (see Concurrency), so id order = commit order | R9 |
| D17 | the workshop's own speaker or a co-speaker registers as attendee | allowed (counts towards the 20) | registrations aren't linked to speakers; the request doesn't forbid it |
| D18 | order of checks (POST) | 404 talk/not-workshop → 400 body → 409 not accepted → 409 duplicate → 409 full | path, input, rules; as acceptance D12 |
| D19 | cancelling a registration | not offered (no DELETE endpoint; Spring answers 405) | not in the request's API |
| D20 | 409 once 20 seats are taken stays 409 after the workshop is withdrawn; registrations of a withdrawn workshop count nowhere else | n/a | registrations are per workshop; nothing else reads them |

## Concurrency

Two invariants must hold under a rush of parallel `POST`s to the same workshop (assume dozens at once; the
platform only cuts off above 80):

1. never more than 20 registrations for a workshop (R5);
2. never two registrations with the same `lower(trim(email))` for a workshop (R6).

How: one transaction per POST that first locks the talk row (`TalkDao.lockForUpdate`, `SELECT … FOR UPDATE`; no
advisory locks), then reads the talk's format and status, counts registrations, checks for the email, and inserts.
Parallel requests for the same workshop run one after the other; each sees the previous ones' rows. The same lock
serialises a registration against a concurrent `withdraw` / status change from the acceptance feature, so a
registration that commits always saw `ACCEPTED`.

Backstops:
- a unique index `ON workshop_registration (talk_id, lower(trim(email)))`; a `DuplicateKeyException` maps to 409;
- the insert can be conditional (`INSERT … SELECT … WHERE (SELECT COUNT(*) …) < 20`), like `TalkDao.insertIfBelow`.

Pool of 5: requests waiting on the row lock each hold a connection; that's fine as long as the transaction uses
only its one connection (no `REQUIRES_NEW`, no second pooled connection while holding the lock) and stays short.
Hikari's default 30 s connection timeout is ample for ~80 short transactions.

## Changes to existing behaviour

- None to existing endpoints or responses. No existing test changes.
- Schema: one new table, appended at the bottom of `schema.sql` with `IF NOT EXISTS` (runs on every start; the
  deployed database keeps its data):
  `workshop_registration (id SERIAL PRIMARY KEY, talk_id INTEGER NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL)`
  plus the unique index above. No change to existing tables or data.
- `src/main/resources/static/openapi.yaml` gets the two operations and the `Registration` schemas.
- Build order: needs the acceptance feature's `PUT /talks/{id}/status` to reach `ACCEPTED`. Build on top of
  `feat/acceptance` or after it's merged; tests set a workshop to `ACCEPTED` through the API (two ratings + accept).

## Assumptions

- Registration is unauthenticated: anyone may register and anyone may read the list (the API has no identity).
- The harness checks the rush with parallel requests to one workshop, expects exactly 20 × 201 and the rest 409,
  and then a GET with exactly 20 entries.
- "In the order they registered" means the order the service accepted them, not a client timestamp.
- The capacity of 20 is the same for every workshop and isn't configurable through the API.

## Open questions for the product owner

Ranked by how likely a wrong guess fails a harness check. Answers go here, with who asked and when.

1. Someone tries to register for a talk that isn't a workshop (a presentation or lightning talk): should that be
   "no such workshop" (we say 404), or "this talk doesn't take registrations" (409)?
2. Someone tries to register for a workshop that hasn't been accepted yet, or was rejected or withdrawn: we refuse
   with 409 (a conflict with the workshop's state). Is that right, or should it be treated as "no such workshop"?
3. The same person registers twice for the same workshop: refuse the second one (we say 409), or treat it as
   already done and answer with their existing registration?
4. Can the organisers see the list of a workshop that isn't accepted (empty, or the people registered before it was
   withdrawn)? We say yes, always.

**Answers (2026-11-06, asked by Sindre Mehus):** the product owner confirmed our answers to all four as written:
not a workshop is 404 (D2), not accepted is 409 (D8), a second registration with the same email is 409 (D9), and
the list is always visible (D15).
