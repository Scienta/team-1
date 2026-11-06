# Programme export: our spec

Feature request: `specs/programme-export/request.md` (harness slug `programme-export`, V 1, released at tick 133).
Status: ready to implement (questions answered from evidence, not by the PO; see the end)

Depends on: **scheduling** (`origin/feat/scheduling`, not on `main` yet; merged into this branch, so this
PR carries scheduling's commits until scheduling is merged). Rows come from its `programme`
table (`room`, `slot`, `talk_id`; PK `(room, slot)`, `UNIQUE (talk_id)`) and times from `Venue.Slot`.
Co-speaker order comes from `co_speaker.id` (already on `main`).

## Rules (from the feature request)

- R1 Two read-only views of the programme: `GET /programme` (JSON list) and `GET /programme.csv` (CSV).
- R2 Both have exactly one row per scheduled talk (one per occupied programme cell), nothing else
  (no blank rows, no summary rows, no empty cells).
- R3 Both list the rows in the same order: earliest slot start first (`S1` .. `S5`), and within a slot
  by room `R1`, `R2`, `R3`.
- R4 Each row has `slot`, `start`, `end`, `room`, `talkId`, `title`, `format`, `speakers`. `start`/`end`
  are the slot's times as in scheduling (`HH:mm`, 24-hour, zero-padded: `09:00`).
- R5 `title`, `format` and `speakers` are the talk's **as they are right now** (at the time of the
  export request), not as they were when it was scheduled.
- R6 Speakers are listed by name: main speaker first, then co-speakers in the order they were added.
  JSON: an array of strings. CSV: one field, names joined by `"; "` (semicolon + one space),
  e.g. `Grace Hopper; Ada Lovelace`.
- R7 CSV is UTF-8 and served as `text/csv`, even when the client sends `Accept: application/json`.
- R8 CSV's first line is exactly `slot,start,end,room,talkId,title,format,speakers`.
- R9 CSV is RFC 4180 with Unix line endings: every line (header included) ends in `\n`, and there is
  no blank line at the end (the body ends with exactly one `\n`, never `\n\n`).
- R10 CSV fields are quoted only when they must be: when they contain a comma `,`, a double quote `"`,
  or a line break (`\n` or `\r`). Inside quotes a `"` is doubled: `Kotlin, "really" fast` is written
  `"Kotlin, ""really"" fast"`. All other fields are written exactly as they are.
- R11 Empty programme: `GET /programme` is `[]`; `GET /programme.csv` is just the header line
  (`slot,start,end,room,talkId,title,format,speakers\n`).

## API

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/programme` | none | 200, `application/json`, array of `ProgrammeRow` |
| GET | `/programme.csv` | none | 200, `text/csv; charset=UTF-8`, the CSV text |

`ProgrammeRow` (JSON, fields in this order):

```json
{
  "slot": "S4",
  "start": "13:30",
  "end": "15:30",
  "room": "R1",
  "talkId": 42,
  "title": "Kotlin, \"really\" fast",
  "format": "WORKSHOP",
  "speakers": ["Grace Hopper", "Ada Lovelace"]
}
```

Matching CSV (header + that row):

```
slot,start,end,room,talkId,title,format,speakers
S4,13:30,15:30,R1,42,"Kotlin, ""really"" fast",WORKSHOP,Grace Hopper; Ada Lovelace
```

CSV column values: `slot`/`room`/`format` are the enum names, `talkId` the plain integer, `start`/`end`
`HH:mm`, `title` as stored, `speakers` the R6 join (then R10 quoting applied to the joined string).

New or changed fields in existing responses: none. Scheduling's `/programme/{room}/{slot}` is unchanged.

## Decisions (where the request is silent)

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | Path overlap `/programme`, `/programme.csv`, `/programme/{room}/{slot}` | Three distinct mappings; `/programme` and `/programme.csv` are literal paths, `/programme/{room}/{slot}` keeps two segments. `/programme.csv` must **not** be reached via suffix matching of `/programme` and must not hit `{room}`. Map `/programme.csv` as its own absolute path (not by concatenating `".csv"` onto the class-level `@RequestMapping("/programme")`). | Boot 2.7 uses PathPatternParser (no suffix matching); explicit literal path avoids surprises |
| D2 | Talk withdrawn / rejected after it was scheduled | Still a row, with its current title, format, speakers; no status column | Scheduling R7/D14: "if a talk changes afterwards, the programme stays as it is"; the request says one row per scheduled talk |
| D3 | Co-speaker added or removed after scheduling | Row shows the current co-speakers (added ones appear at the end in add order, removed ones vanish) | R5 "as they are right now" |
| D4 | Speaker renamed via `PUT /speakers/{id}` after scheduling | Row shows the new name | R5; names are looked up at export time, never copied into `programme` |
| D5 | Name / title value | Exactly as returned by `GET /speakers/{id}` / `GET /talks/{id}` (no trimming, no case change) | "written as they are" |
| D6 | Two different speakers with the same name | Both names listed (duplicates kept) | one entry per person |
| D7 | Name containing `;` | Written as is (no escaping, no quoting unless R10 applies) | request defines quoting only for `,` `"` line breaks |
| D8 | Field with leading/trailing spaces | Not quoted, written as is | R10: quote only for `,` `"` line breaks |
| D9 | Line break inside a quoted field | Kept byte-for-byte inside the quotes (`\r\n` in a title stays `\r\n`); only record terminators are forced to `\n` | RFC 4180 allows it; don't alter data |
| D10 | `Content-Type` of CSV | `text/csv; charset=UTF-8` | same as existing `/admin/talks.csv`; `server.servlet.encoding.force=true` adds a charset anyway; a `text/csv` prefix check still passes |
| D11 | `GET /programme.csv` with `Accept: application/json` (or `text/html`, `*/*`, none) | 200 CSV, never 406 | R7 explicit. Implementer: set content type explicitly (e.g. write the response directly or `ResponseEntity` with a preset `Content-Type` and no `produces=` restriction) and test with `Accept: application/json` |
| D12 | `GET /programme` with `Accept: text/csv` or other | 200 JSON, never 406 | lenient; the request only defines JSON there |
| D13 | No BOM, no `Content-Disposition` requirement | No BOM. `Content-Disposition: attachment; filename=programme.csv` may be added (harmless) | BOM would break "starts with exactly this header line" |
| D14 | Query parameters on either endpoint | Ignored | read-only full views |
| D15 | Trailing slash `/programme/` | 200, same as `/programme` | scheduling D16: trailing slashes accepted |
| D16 | Other methods (`POST`/`PUT`/`DELETE` on `/programme` or `/programme.csv`) | 405 (Spring default) | read-only (R1) |
| D17 | `HEAD` | Spring default (headers of GET, no body) | no extra work |
| D18 | JSON number vs string | `talkId` JSON integer; `speakers` always an array (at least the main speaker) | per OpenAPI in request |
| D19 | Ordering implementation | Sort by `Venue.Slot` ordinal, then `Venue.Room` ordinal (not by `start` string) | equal to start-time order for the fixed venue; robust |
| D20 | Missing speaker row for a talk's speaker id (should not happen: no delete endpoints) | Skip that name, never 500 | defensive |

## Concurrency

Read-only; no locks. Default isolation (`READ COMMITTED`): **never** `REPEATABLE_READ` or
`SERIALIZABLE` (team 3 put `@Transactional(isolation = REPEATABLE_READ)` on its export and broke
workshop-registration's 20-seat check and ratings with `could not serialize access` 500s; their
`REVIEW.md`, 14:07 incident). Each request reads the programme, talks, speakers and co-speakers in one
read-only transaction (or one joined SQL query) so a row is never half from before and half from after a
concurrent change. Keep it to one connection per request (pool is 5): at most 15 rows, so a single
join or a few batched queries, not an extra connection per row. Don't use `FOR UPDATE`: exports must not
block or be blocked by scheduling `PUT`/`DELETE` beyond normal MVCC.

## Changes to existing behaviour

- None to existing responses. Scheduling's `GET/PUT/DELETE /programme/{room}/{slot}` and their tests
  stay as they are.
- No schema change (reads `programme`, `talk`, `speaker`, `co_speaker`).
- `openapi.yaml`: add `/programme` and `/programme.csv` with `ProgrammeRow`.
- Don't reuse `Utils.csv`: it quotes on `;` and replaces `"` with `'`, which is not RFC 4180.

## Assumptions

- A1 Scheduling merges with the shapes on `origin/feat/scheduling` (commit `a8f018c`): table `programme(room TEXT,
  slot TEXT, talk_id BIGINT)` holding occupied cells only, room/slot stored as enum names `R1`..`R3`,
  `S1`..`S5`; `Venue.Slot` carries `start`/`end` strings; `ProgrammeController` is
  `@RequestMapping("/programme")` with mappings `/{room}/{slot}` and `/{room}/{slot}/`. If the table,
  column names, stored format of room/slot or slot times change, the export query and D1 change too.
- A2 Scheduling never removes or edits a programme row when a talk changes (its R7/D14). If that
  changes (e.g. withdraw auto-unschedules), D2 changes with it.
- A3 Talks and speakers are never deleted (no delete endpoints today); title and format of a talk
  never change after submission, so "right now" matters mainly for speaker names and co-speakers.
- A4 Co-speaker order "in the order they were added" = `co_speaker.id` ascending (as `CoSpeakerDao.findByTalk`).
  A co-speaker removed and re-added goes to the end.
- A5 The harness compares the CSV body byte-exactly and checks `Content-Type` starts with `text/csv`
  (charset parameter tolerated).

## Open questions for the product owner

Not put to the PO. Answered on 2026-11-06 from the feature requests and from team 5's public repo
(`Scienta/team-5`): team 5 claimed programme-export at tick 152 with the same three choices, and its
`REVIEW.md` reports all 3 harness checks OK from the first tick. That shows the checks accept these
answers, not that they test each case.

1. Withdrawn or rejected after it was scheduled: **still in the export (D2).** Scheduling's request:
   "If a talk changes afterwards, the programme stays as it is"; this request: "everything on the
   programme". Team 5: same (their E1).
2. `Content-Type`: **`text/csv; charset=UTF-8` (D10).** Team 5 answers `text/csv;charset=UTF-8` under any
   `Accept` (their E8).
3. `406` on `/programme`: **never; always 200 JSON (D12).** Team 5 has no `produces` on `/programme`.
