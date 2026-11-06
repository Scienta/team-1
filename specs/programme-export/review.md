# Programme export: review triage

Independent review of `feat/programme-export` against `specs/programme-export/spec.md`. Verdict: ready after fixes.

| # | Finding | Severity | Verdict | What we did, or why not |
|---|---|---|---|---|
| 1 | `examples/programme-export.hurl` claims "safe on a shared database" but DELETEs R3/S5 and leaves it empty; `clearWholeProgramme()` wipes the programme if the Vitest suite targets TEAM_URL | should-fix | Agree in part | The "safe" claim was wrong: the header now says it isn't safe on a database whose programme matters, and to run it locally or on a scratch database. We didn't build the restore: Hurl can't capture `talkId` from a cell that may be empty (404) or branch on it, so a GET-then-PUT-back can't cover both cases. `examples/scheduling.hurl` already mutates cells the same way. `black-box-tests/README.md` and `decisions.md` now say the suite must not target TEAM_URL. (19202b1) |
| 2 | Hurl doesn't assert D17 (HEAD) | nit | Agree | Added `HEAD /programme.csv` → 200, `text/csv`, empty body; 19 requests green against a local jar. (19202b1) |
| 3 | `ProgrammeExportTest` concurrency assertion `endsWith("\n\n") && !equals(HEADER)`: the second clause is always true | nit | Agree | Simplified to `assertFalse(res.body().endsWith("\n\n"))`. (19202b1) |

`./mvnw -q package`: green, 204 tests, 0 failures.
