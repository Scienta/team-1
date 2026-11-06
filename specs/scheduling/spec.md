# Programme scheduling: our spec

Feature request: `specs/scheduling/request.md` (harness slug `scheduling`, V 3, released at tick 100).
Status: go without PO answers (2026-11-06) — Q1–Q3 unanswered; D9/D12/D2 stand

Depends on: **acceptance** (merged on `main`; claim optional). Only `ACCEPTED` talks may be scheduled.

## Rules (from the feature request)

- R1 Venue is fixed: rooms `R1`–`R3`, slots `S1`–`S5` with the starts/ends below. Nobody can add or change rooms or slots. Times are `HH:mm` 24-hour (`09:00`, not `9:00`).

  | Slot | Start | End |
  |---|---|---|
  | `S1` | 09:00 | 10:00 |
  | `S2` | 10:15 | 11:15 |
  | `S3` | 11:30 | 12:30 |
  | `S4` | 13:30 | 15:30 |
  | `S5` | 15:45 | 16:45 |

- R2 The committee can put a talk in a room+slot (`PUT`), look it up (`GET`), and take it out (`DELETE`). A removed talk may be placed elsewhere later.
- R3 Only talks with status `ACCEPTED` go on the programme.
- R4 Fit: a `WORKSHOP` needs the long slot (`S4` only). Every other format fits any slot (including `S4`).
- R5 At most one talk per `(room, slot)`. A talk appears on the programme at most once. To move a talk, `DELETE` it first, then `PUT` elsewhere.
- R6 No speaker double-booking in a slot: the main speaker and all co-speakers of talks in the **same slot** (any rooms) must be pairwise distinct people.
- R7 Rules are checked when a talk is **put** on the programme. Later changes to the talk (status, co-speakers, format) do **not** auto-fix the programme.
- R8 Concurrent programme edits must never leave the programme breaking R3–R6.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| PUT | `/programme/{room}/{slot}` | `{"talkId": n}` | 200, `Entry` |
| GET | `/programme/{room}/{slot}` | — | 200, `Entry` |
| DELETE | `/programme/{room}/{slot}` | — | 200, see D8 |

`Entry`:

```json
{
  "room": "R1",
  "slot": "S4",
  "start": "13:30",
  "end": "15:30",
  "talkId": 42
}
```

`room` / `slot` path params: exact enum capitals. `start`/`end` always from R1’s table for that slot (not stored independently).

New or changed fields in existing Talk responses: **none** (programme is a separate resource).

## Decisions (where the request is silent)

Conventions: 400 = invalid request shape/path tokens; 404 = missing resource; 409 = business rule / occupancy conflict (matches talks limit & co-speakers).

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | unknown / wrong-case / extra room or slot in path | 404 | path resource; don’t invent rooms |
| D2 | `GET` on an empty cell | 404 | nothing to return; like missing talk |
| D3 | `PUT` body missing/`talkId` missing/null/not integer | 400 | invalid request |
| D4 | `PUT` unknown `talkId` | 404 | talk is the resource being placed |
| D5 | `PUT` talk not `ACCEPTED` (any other status) | 409 | state refuses scheduling (R3) |
| D6 | `PUT` workshop into `S1`/`S2`/`S3`/`S5` | 409 | does not fit (R4) |
| D7 | `PUT` when cell occupied by **another** talk | 409 | one talk per cell (R5) |
| D8 | `PUT` when this talk is already on the programme in **another** cell | 409 | talk only once; must DELETE first (R5) |
| D9 | `PUT` same talk into the **same** cell again (idempotent) | 200, same `Entry` | no rule break; avoids flaky double-clicks |
| D10 | `PUT` would double-book a speaker/co-speaker in that slot | 409 | R6 |
| D11 | `DELETE` on empty cell | 404 | like removing a non-co-speaker |
| D12 | `DELETE` success | 200 with the `Entry` that was removed (room, slot, start, end, talkId) | useful for clients; request only describes DELETE |
| D13 | order of `PUT` checks | 404 room/slot → 400 body → 404 talk → 409 status → 409 fit → 409 same-talk-elsewhere → 409 cell occupied → 409 speaker clash → write | path/input before rules |
| D14 | after accept→schedule, talk withdrawn/rejected later | programme row stays until DELETE (R7) | harness: “if a talk changes afterwards, the programme stays” |
| D15 | co-speakers added after scheduling | programme stays; clash not re-checked (R7) | same |
| D16 | trailing slash on `/programme/.../` | accept like speakers (legacy) | consistency |

## Concurrency

R8: scheduling `PUT`/`DELETE` run in a transaction that:

1. Locks the target programme cell row (`SELECT … FOR UPDATE` on `(room, slot)`, or insert placeholder then lock — implementer’s choice).
2. Locks the talk row being placed (`TalkDao.lockForUpdate`) so status/co-speakers are stable for the check.
3. For R6, locks or consistently reads all programme rows in that **slot** (e.g. `SELECT … FROM programme WHERE slot = ? FOR UPDATE`) before checking speaker sets.
4. Enforces uniqueness with DB constraints: `UNIQUE (room, slot)`, `UNIQUE (talk_id)` so races fail safely even if a check is missed.

No advisory locks; pool size 5.

## Changes to existing behaviour

- None for speakers/talks/ratings/co-speakers/acceptance JSON, except talks can be referenced by id from programme.
- New table `programme` (name TBD in implementation) appended in `schema.sql` with `IF NOT EXISTS`.
- Acceptance remains prerequisite for happy-path tests (create talk → rate ×2 → accept → schedule).

## Assumptions

- A1 “Long slot” means only `S4` (120 minutes wall clock); workshops are 120 minutes; other formats’ durations all fit in every slot including `S4`.
- A2 Committee is unauthenticated (same as acceptance decisions).
- A3 Speaker clash uses main `speakerId` plus `coSpeakerIds` **as stored at PUT time**.
- A4 Acceptance is already on `main` (merged); scheduling may ship before acceptance is harness-claimed.

## Open questions for the product owner

Ranked by harness-failure risk. **Person asks; answers go here.**

1. **Idempotent re-PUT of the same talk into the same cell — 200 or 409?**  
   We propose **200** (D9). Wrong guess is low risk but visible in double-click tests.

2. **DELETE response body — 200+Entry, 200 empty, or 204?**  
   We propose **200 + removed Entry** (D12). Request is silent.

3. **GET empty cell — 404 or 200 with `talkId: null`?**  
   We propose **404** (D2).

If you say **go** without PO answers, we keep D9/D12/D2 as above and mark questions unanswered.

## Out of scope

- Changing rooms/slots; programme export (separate drip); UI.
