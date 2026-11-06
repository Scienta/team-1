## Programme export (`programme-export`)

Feature request: `specs/programme-export/request.md` · Our spec: `specs/programme-export/spec.md`

**Stacked on scheduling.** This branch merges `feat/scheduling` (and current `main`, commit `824fcce`), so
until scheduling's PR is merged this diff carries its commits too. Merge scheduling first, or merge this one
and close scheduling's. `824fcce` resolves the conflicts between scheduling and main's workshop-registration
(additive, plus one shared helper `createAcceptedTalk` that now takes a format or a `NewTalkInput`) and moves
`ProgrammeTest` to Jackson 3 for Spring Boot 4.

### What changed

- `GET /programme`: JSON list of `ProgrammeRow` (`slot`, `start`, `end`, `room`, `talkId`, `title`, `format`,
  `speakers`), one per occupied cell, slot then room order. Always JSON, whatever `Accept`.
- `GET /programme.csv`: the same rows as RFC 4180 CSV, `text/csv;charset=UTF-8` under any `Accept`, exact
  header line, `\n` endings, no blank line at the end, quoting only on `,` `"` `\n` `\r`, speakers joined by `"; "`.
- Rows come from one SQL statement (`ProgrammeDao.exportRows`), read-only, default isolation, no locks. Names,
  titles and co-speakers are read at export time.
- `ProgrammeController` maps full paths (no class-level `@RequestMapping`) so `/programme.csv` is its own literal path;
  the cell routes `/programme/{room}/{slot}` are unchanged.
- New CSV writer; `Utils.csv` (not RFC 4180) is not reused.
- `openapi.yaml`, `examples/programme-export.hurl`, Vitest `black-box-tests/src/suites/programme-export.test.ts`,
  `black-box-tests/decisions.md`.

### Rules and decisions, and the tests that prove them

All in `ProgrammeExportTest`.

| Id | Rule or decision | Test |
|---|---|---|
| R1–R4, R8, D18, D19 | One row per scheduled talk, fields, header, slot-then-room order | `r1r2r3r4_oneRowPerScheduledTalkInSlotThenRoomOrder` |
| R4 | UTF-8 names in CSV and JSON | `r4_utf8NamesSurviveInCsvAndJson` |
| R5, D3, D4 | Current speakers and names, not those at scheduling time | `r5d3d4_rowShowsCurrentSpeakersNotThoseAtSchedulingTime` |
| R6, A4 | Main speaker first, co-speakers in add order; re-added goes last | `r6_mainSpeakerFirstThenCoSpeakersInAddOrder`, `a4_removedAndReAddedCoSpeakerGoesToTheEnd` |
| R7, D10, D11, D13 | `text/csv;charset=UTF-8` whatever `Accept`, no BOM | `r7d10d11_csvIsTextCsvUtf8WhateverTheAcceptHeader` |
| R9 | `\n` endings, no blank line at the end | `r9_csvHasUnixLineEndingsAndNoBlankLineAtEnd` |
| R10, D9 | Quote only when needed, `""` inside, line breaks kept | `r10_fieldWithCommaAndQuotesIsQuotedWithDoubledQuotes`, `r10_quoteOnlyFieldIsQuoted`, `r10_speakersJoinedThenQuotedWhenANameHasAComma`, `r10d9_lineBreaksInsideFieldsAreQuotedAndKeptByteForByte` |
| D5, D7, D8 | `;` and surrounding spaces written as is | `d7d8_semicolonAndSurroundingSpacesAreWrittenAsIs` |
| R11 | Empty programme: `[]` / header only | `r11_emptyProgrammeIsEmptyArrayAndHeaderOnly` |
| D2 | Withdrawn after scheduling stays | `d2_withdrawnTalkStaysInTheExport` |
| D6 | Same-name speakers both listed | `d6_twoSpeakersWithTheSameNameAreBothListed` |
| D12 | `/programme` JSON whatever `Accept` | `d12_jsonViewIsJsonWhateverTheAcceptHeader` |
| D1 | Export paths don't shadow cell paths | `d1_exportPathsDoNotShadowCellPaths` |
| D14, D15, D16, D17 | Query ignored, trailing slash, 405, HEAD | `d14_…`, `d15_…`, `d16_writeMethodsOnExportPathsAre405`, `d17_headReturnsHeadersWithoutBody` |
| D20 | Missing co-speaker row skipped, not 500 | `d20_missingCoSpeakerRowIsSkippedNot500` |
| Concurrency | Exports during scheduling edits always 200 and well-formed | `concurrency_exportsDuringSchedulingEditsAreAlways200AndConsistent` |

`./mvnw package`: 204 tests, all green. Black box: `examples/programme-export.hurl` (19 requests, green against a
local jar) and the Vitest suite (run in a `node:22-alpine` container against a local jar: 152 tests green).

### Review

Reviewer findings and what we did (`specs/programme-export/review.md`):

| # | Finding | Verdict | What we did, or why not |
|---|---|---|---|
| 1 | Hurl and `clearWholeProgramme()` not safe on a shared database | Agree in part | Documented as local-only (Hurl header, black-box README, decisions). Restore not built: Hurl can't branch on an empty cell. **See below.** |
| 2 | Hurl didn't assert D17 (HEAD) | Agree | Added `HEAD /programme.csv` → 200 |
| 3 | Always-true clause in a concurrency assertion | Agree | Simplified |

### Open questions for the product owner

Not put to the PO; answered from the feature requests and team 5's public repo (they claimed this feature at
tick 152 with the same choices, 3/3 OK from the first tick). Details in the spec.

- Withdrawn talk after scheduling: stays in the export (answered from evidence, not PO).
- `Content-Type`: `text/csv;charset=UTF-8` (answered from evidence, not PO).
- `/programme` never 406 (answered from evidence, not PO).

### Before claiming (after merge, a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] **Not** `examples/programme-export.hurl` against TEAM_URL as is: it empties cell R3/S5 on the live
      programme (as `examples/scheduling.hurl` does with its cells). Instead: `curl $TEAM_URL/programme` and
      `curl -i -H 'Accept: application/json' $TEAM_URL/programme.csv` by hand, or run the Hurl file only after
      confirming R3/S5 is empty.
- [ ] harness traffic for `GET /programme` / `/programme.csv` in the logs answered 200 (team 5 saw it arrive
      before claiming)
- [ ] no 5xx in `.github/fagdag/logs.sh`
- [ ] harness: every claimed feature still all OK
- [ ] a person claims; claim-log row added to `REVIEW.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
