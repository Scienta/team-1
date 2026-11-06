# Review triage: scheduling

Branch: `feat/scheduling`  
Reviewed against: `specs/scheduling/spec.md`

| # | Finding | Severity | Verdict | What we did, or why not |
|---|---|---|---|---|
| 1 | ProgrammeTest only concurrent same-cell PUTs; missing parallel speaker-clash across rooms and same-talk into two slots | should-fix | **Accepted** | Added `r8_concurrentSameSpeakerAcrossRooms_neverBothPlaced` and `r8_concurrentSameTalkIntoTwoSlots_atMostOnePlacement`. Spec R8 requires concurrent edits never break R3–R6; same-cell alone does not cover R6/R5 races. |
| 2 | D13 only unknown-room-beats-bad-body; need stacked 409 order cases | should-fix | **Accepted** | Added `d13_statusConflictBeatsCellOccupied` (D5 before D7) and `d13_talkAlreadyElsewhereBeatsCellOccupied` (D8 before D7). |
| 3 | `lockSlot` ignores empty result | nit | **Accepted** | `ProgrammeDao.lockSlot` now requires exactly one locked row; otherwise `IllegalStateException` (seed/`programme_slot` misconfigured). |
| 4 | `examples/scheduling.hurl` missing wrong-case room/slot and DELETE Entry times | nit | **Accepted** | Hurl now PUTs `r1`/`s1` → 404 and asserts `start`/`end` on DELETE Entry (D1, D12). |
| 5 | D9 + R7: re-PUT same cell after withdraw not tested | nit | **Accepted** | Added `d9r7_rePutSameCellAfterWithdrawIsStill200` (idempotent re-PUT before status check). |

No findings rejected.
