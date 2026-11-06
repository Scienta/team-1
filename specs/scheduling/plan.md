# Implementation plan: programme scheduling

Branch: `feat/scheduling`  
Spec: `specs/scheduling/spec.md`  
Stack: Spring Boot 2.7 / Java 11 / Postgres (match acceptance + co-speakers patterns).

## Prerequisites

- [ ] Person claims **scheduling** on the harness (agents can’t). Acceptance claim optional — code is already on `main`.
- [ ] Human gate on `spec.md` (answer PO Q1–Q3 or say **go**).
- [ ] Local: `docker compose up -d`, then TDD via `./mvnw test` / package.

## Approach

Mirror `TalkStatusService` / `CoSpeakerService`: thin controller → service with `@Transactional` → DAO with `FOR UPDATE` + unique constraints. Fixed rooms/slots as an in-code enum/table (not DB-editable). Programme occupancy in a new `programme` table.

---

## Step 1 — Domain constants (rooms & slots)

**Files:** e.g. `no.scienta.cfp.programme.Room`, `Slot` (or one `Venue` helper).

- Enums / maps for `R1`–`R3`, `S1`–`S5` with `start`/`end` strings exactly as R1.
- `parseRoom` / `parseSlot`: invalid → empty Optional (controller maps to 404).
- Unit-test: all five slots’ times; reject `r1`, `S6`, empty.

## Step 2 — Schema

**File:** `src/main/resources/schema.sql` (append; keep `IF NOT EXISTS`).

```sql
CREATE TABLE IF NOT EXISTS programme (
  room    TEXT NOT NULL,
  slot    TEXT NOT NULL,
  talk_id BIGINT NOT NULL REFERENCES talk(id),
  PRIMARY KEY (room, slot),
  UNIQUE (talk_id)
);
```

No Flyway; Testcontainers recreate schema from this file.

## Step 3 — Failing API tests first (TDD)

**File:** e.g. `ProgrammeApiTest` (MockMvc or RestAssured style matching existing `*ApiTest`).

Cover, in order of harness risk:

1. PUT ACCEPTED talk → 200 Entry with correct start/end.
2. GET occupied → same Entry; GET empty → 404.
3. DELETE → 200 Entry then GET 404; DELETE empty → 404.
4. PUT unknown room/slot → 404; bad body → 400; unknown talk → 404.
5. PUT non-ACCEPTED (SUBMITTED / REJECTED / WAITLISTED / WITHDRAWN) → 409.
6. PUT WORKSHOP into S1 → 409; WORKSHOP into S4 → 200.
7. PUT second talk same cell → 409; PUT same talk same cell again → 200 (D9).
8. PUT talk already in another cell → 409 without DELETE.
9. Speaker clash: two talks, same main speaker, same slot different rooms → second 409.
10. Co-speaker clash: talk A main=1; talk B coSpeaker includes 1; same slot → 409.
11. After schedule, withdraw talk → GET programme still returns Entry (R7).
12. Concurrent PUT two talks into same cell (optional stress): exactly one 200.

Reuse helpers from acceptance tests (rate twice + accept).

## Step 4 — DAO + model

**Files:** `ProgrammeEntry` (immutable DTO), `ProgrammeDao`.

- `find(room, slot)`, `findByTalkId(talkId)`.
- `lockCell(room, slot)` / `lockSlot(slot)` via `SELECT … FOR UPDATE`.
- `insert`, `delete`; map to Entry with times from `Slot`.

## Step 5 — Service

**File:** `ProgrammeService`.

`put(room, slot, talkId)`:

1. Resolve room/slot or fail not-found.
2. Lock slot rows + target cell; lock talk.
3. Apply D3–D10 in D13 order; throw domain exceptions mapped like acceptance (`NotFound`, `BadRequest`, `Conflict`).
4. Insert or no-op if D9.
5. Return Entry.

`get` / `delete`: lock cell; empty → not found; delete returns removed Entry.

Speaker set for a talk: `{speakerId} ∪ coSpeakerIds` (load co-speakers via existing DAO).

## Step 6 — Controller + errors

**File:** `ProgrammeController` `@RequestMapping("/programme")`.

- `PUT|GET|DELETE /{room}/{slot}` (+ trailing-slash variants if others have them).
- Wire exception handler entries if not already generic.

## Step 7 — Verify

- `./mvnw test` green (Docker required).
- Smoke: accept a talk, `PUT /programme/R1/S1`, GET, DELETE.
- Optional: black-box Vitest cases under `black-box-tests/` (not required for harness; nice for CI).
- Push branch → PR → merge to `main` deploys; person watches harness checks for `scheduling`.

## Out of scope this PR

- Claiming features on the harness.
- Programme export drip.
- Changing acceptance or co-speaker APIs.

## Risk notes

| Risk | Mitigation |
|---|---|
| Wrong DELETE/GET empty status | PO Q2/Q3; default D12/D2 |
| Speaker clash misses co-speakers | Explicit test step 10 |
| Race double-book cell | UNIQUE + row locks |
| Acceptance not claimed yet | Scheduling only needs ACCEPTED in DB; ship after acceptance merge (done) |
