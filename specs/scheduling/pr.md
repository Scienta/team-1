## Programme scheduling (`scheduling`)

Feature request: `specs/scheduling/request.md` · Our spec: `specs/scheduling/spec.md`

### What changed

- New `PUT` / `GET` / `DELETE` `/programme/{room}/{slot}` to place ACCEPTED talks into fixed rooms `R1`–`R3` and slots `S1`–`S5`.
- Fit rules (workshops only in `S4`), one talk per cell, talk at most once, no speaker/co-speaker double-booking in a slot.
- Row locks (`programme_slot` + talk) and UNIQUE constraints for concurrent edits; later talk changes do not auto-fix the programme.

### Rules and decisions, and the tests that prove them

| Id | Rule or decision | Test |
|---|---|---|
| R1 | Fixed rooms/slots and times | `VenueTest#r1_slotTimesMatchTheVenueTable`, `ProgrammeTest#r1r2_putAcceptedTalkReturnsEntryWithFixedTimes` |
| R2 | PUT/GET/DELETE; re-place after DELETE | `r2d2_…`, `r2d11d12_…`, `r2_removedTalkCanBePlacedElsewhere` |
| R3/D5 | Only ACCEPTED | `r3d5_nonAcceptedTalkIs409` |
| R4/D6 | Workshop only S4 | `r4d6_workshopOnlyFitsS4`, `r4_nonWorkshopFitsAnySlotIncludingS4` |
| R5/D7/D8/D9 | One per cell; talk once; idempotent same cell | `r5d7_…`, `r5d8_…`, `d9_…` |
| R6/D10 | Speaker/co-speaker clash in slot | `r6d10_sameMainSpeaker…`, `r6d10_coSpeakerClash…` |
| R7/D14/D15 | Later changes leave programme | `r7d14_…`, `r7d15_…`, `d9r7_…` |
| R8 | Concurrent edits safe | `r8_concurrentPutsIntoSameCell_…`, `r8_concurrentSameSpeakerAcrossRooms_…`, `r8_concurrentSameTalkIntoTwoSlots_…` |
| D1–D4, D11–D13, D16 | Path/body/404/order/slash | `d1_…`, `d3_…`, `d4_…`, `d13_…`, `d16_…` |

`./mvnw package`: all green (ProgrammeTest + VenueTest + existing suites). Black box: `examples/scheduling.hurl`.

### Review

Reviewer findings and what we did (`specs/scheduling/review.md`):

| # | Finding | Verdict | What we did, or why not |
|---|---|---|---|
| 1 | R8 same-cell only | Accepted | Cross-room speaker race + same-talk two slots |
| 2 | D13 partial | Accepted | Stacked 409 order tests |
| 3 | `lockSlot` empty | Accepted | Require exactly one locked row |
| 4 | hurl nits | Accepted | Wrong-case path + DELETE times |
| 5 | D9 after withdraw | Accepted | New test |

No findings rejected.

### Open questions for the product owner

1. Idempotent re-PUT same cell — **200** (D9), unanswered.
2. DELETE body — **200 + Entry** (D12), unanswered.
3. GET empty — **404** (D2), unanswered.

(Shipped on “go without PO answers”.)

### Before claiming (after merge, a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] `examples/scheduling.hurl` green against TEAM_URL
- [ ] no 5xx in `.github/fagdag/logs.sh`
- [ ] harness: every claimed feature still all OK
- [ ] a person claims; claim-log row added to `REVIEW.md`
