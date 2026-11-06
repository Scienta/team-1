# Acceptance state machine: our spec

Feature request: `specs/acceptance/request.md` (harness slug `acceptance`, V 2, released at tick 20).
Status: draft

## Rules (from the feature request)

- R1 The committee decides on a talk with `PUT /talks/{id}/status`: `ACCEPTED`, `REJECTED` or `WAITLISTED`.
- R2 A `SUBMITTED` or `WAITLISTED` talk can be accepted, rejected or waitlisted. `ACCEPTED` and `REJECTED` are
  final: no further decision.
- R3 A talk can only be accepted if at least two **different** reviewers have rated it. A reviewer who rated it
  twice counts once. Rejecting and waitlisting need no ratings.
- R4 The talk's own main speaker can withdraw it with `POST /talks/{id}/withdraw` `{"speakerId": n}`, from
  `SUBMITTED`, `WAITLISTED` or `ACCEPTED`. Not from `REJECTED` or `WITHDRAWN`.
- R5 Nobody but the talk's speaker can withdraw it.
- R6 `WITHDRAWN` is final: no decision and no withdrawal afterwards.
- R7 The current status shows wherever a talk is shown: `GET /talks/{id}`, `GET /talks`,
  `GET /speakers/{id}/talks`, the co-speaker responses, and the decision/withdraw responses themselves.
- R8 A decision or withdrawal that isn't allowed changes nothing.
- R9 Concurrent decisions and withdrawals on the same talk never leave it in a state the rules don't allow
  (for example two clicks: one `REJECTED`, one `ACCEPTED`, both on a `WAITLISTED` talk: exactly one wins).

State table (rows: current status, columns: request). ✔ = 200 and the new status; ✘ = refused, changes nothing.

| From \ To   | ACCEPTED        | REJECTED | WAITLISTED | withdraw |
|-------------|-----------------|----------|------------|----------|
| SUBMITTED   | ✔ if ≥2 reviewers | ✔      | ✔          | ✔        |
| WAITLISTED  | ✔ if ≥2 reviewers | ✔      | see D6     | ✔        |
| ACCEPTED    | ✘               | ✘        | ✘          | ✔        |
| REJECTED    | ✘               | ✘        | ✘          | ✘        |
| WITHDRAWN   | ✘               | ✘        | ✘          | ✘        |

## API

| Method | Path | Body | Success |
|---|---|---|---|
| PUT | `/talks/{id}/status` | `{"status": "ACCEPTED" \| "REJECTED" \| "WAITLISTED"}` | 200, the Talk |
| POST | `/talks/{id}/withdraw` | `{"speakerId": n}` | 200, the Talk |

New or changed fields in existing responses: `Talk.status` can now be any of `SUBMITTED`, `ACCEPTED`,
`REJECTED`, `WAITLISTED`, `WITHDRAWN`. No new fields.

## Decisions (where the request is silent)

Conventions from the code and from the harness so far: 400 = the request is invalid in itself or for this
talk (co-speakers/check-2 wanted 400 for "main speaker as co-speaker"), 404 = path resource missing,
409 = a rule refuses it given the talk's current state (the 3-talk limit and the third co-speaker are 409,
both green on the harness).

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | unknown talk id (decision or withdraw) | 404 | same as `GET /talks/{id}` |
| D2 | `status` missing, null, not a string, unknown, lowercase, or `SUBMITTED`/`WITHDRAWN` | 400 | not one of the three decisions; capitals only, like formats and levels |
| D3 | body missing or not JSON | 400 | Spring's default for an unreadable body |
| D4 | decision not allowed from the current status (✘ in the table) | 409 | the talk's state refuses it, like the 3-talk limit |
| D5 | `ACCEPTED` with fewer than 2 distinct reviewers | 409 | state-dependent (more ratings make it pass), like D4 |
| D6 | `WAITLISTED` on a talk that is already `WAITLISTED` | 200, unchanged | "can still be accepted or rejected later" doesn't forbid it, and it breaks no rule; **PO question 2** |
| D7 | the same final decision again (`ACCEPTED` → `ACCEPTED`) | 409 | "the decision is final"; any decision on a final talk is refused |
| D8 | withdraw: `speakerId` missing, null or not an integer | 400 | invalid request |
| D9 | withdraw: `speakerId` is a registered speaker who isn't the talk's main speaker (including a co-speaker) | 403 | "nobody else can withdraw it": who asks, not the talk's state; **PO question 1** |
| D10 | withdraw: `speakerId` isn't a registered speaker | 403 | also "somebody else"; we don't reveal which ids exist |
| D11 | withdraw from `REJECTED` or `WITHDRAWN` | 409 | the state refuses it (D4); checked after D8–D10, so a stranger gets 403 whatever the state |
| D12 | order of checks | 404 talk → 400 body → 403 who (withdraw) → 409 state/ratings | path first, then input, then permission, then rules |
| D13 | does a withdrawn or rejected talk still count towards the speaker's 3-talk limit? | yes, unchanged | the request doesn't say the limit changes; **PO question 3** |
| D14 | `/admin/stats` `byStatus`, `/admin/talks.csv` status column | show the new statuses as they are | they already read `talk.status` |

## Concurrency

R9: both endpoints lock the talk row (`TalkDao.lockForUpdate`, `SELECT … FOR UPDATE`, no advisory locks) at the
start of the transaction, then read the status and ratings, check, and update. Two concurrent requests on the
same talk run one after the other; the second sees the first's result and is refused if it's no longer
allowed. As a second guard the update is conditional: `UPDATE talk SET status = ? WHERE id = ? AND status = ?`
(the status we checked); 0 rows means 409. A rating arriving concurrently can only add reviewers, so it can't
make an accept invalid.

## Changes to existing behaviour

- `Talk.status` takes new values in every Talk response. Nothing else changes for base or co-speaker features.
- No schema change: `talk.status TEXT NOT NULL DEFAULT 'SUBMITTED'` already exists.
- No existing test should change. Ratings keep working on talks in any status (the request doesn't restrict it).
- Later: workshop-registration takes registrations only for `ACCEPTED` workshops, and keeps registrations of
  ones withdrawn later.

## Assumptions

- "The committee" isn't authenticated: anyone may call `PUT /talks/{id}/status` (the API has no committee identity).
- "Reviewer" means a distinct `reviewerId` in the ratings; the `rating` table's primary key `(talk_id, reviewer_id)`
  already makes a second rating replace the first, so `ratingCount` is the number of distinct reviewers.
- A co-speaker is not "the speaker" of the talk for withdrawing (R4 says "their own talk", and the speaker's
  own talk list only shows talks where they are the main speaker).

## Open questions for the product owner

Ranked by how likely a wrong guess fails a harness check. Answers go here, with who asked and when.

1. Can a co-speaker withdraw the talk, or only the main speaker? (We say only the main speaker; others get 403.)
2. Is waitlisting an already waitlisted talk allowed (no change) or refused? (We say allowed, 200.)
3. Does a withdrawn or rejected talk still count towards the speaker's limit of 3 talks? (We say yes.)
4. Can a talk still be rated after it's been decided or withdrawn? (We say yes, nothing changes for ratings.)
