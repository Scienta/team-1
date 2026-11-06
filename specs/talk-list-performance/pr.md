## Talk lists and admin reports in a handful of queries (`fix/talk-list-performance`)

On production (~6,500 talks) `GET /talks` took 25 s and `/admin/stats` 35 s: N+1 queries (ratings and co-speakers per
talk, speaker per talk in the reports). The black-box suite timed out on them against TEAM_URL, and harness checks
that list talks were at risk. Responses are unchanged byte for byte.

### What changed

- List endpoints load ratings and co-speakers for all their talks in one query each (`ConferenceService.enrichAll`,
  `RatingDao.statsByTalk`, `CoSpeakerDao.findByTalks`, `= ANY(?)` with one array parameter).
- `/admin/stats` and `/admin/talks.csv` read speaker names in one query (`SpeakerDao.namesById`); a talk whose speaker
  row is missing still gives the CSV's 404, as before.
- Unchanged: `GET /talks/{id}`, every write path, `schema.sql` (EXPLAIN shows the existing PK / unique indexes are used),
  `openapi.yaml`.

### Evidence (`specs/talk-list-performance/evidence.md`)

Main's jar vs this branch's jar on the same local Postgres with 3,000 speakers, 7,000 talks, 21,000 ratings, 2,100
co-speakers:

| Endpoint | Queries | Time |
|---|---|---|
| `GET /talks` | 14,002 → 4 | 24.6 s → 0.21 s |
| `GET /talks?sort=rating` | 14,005 → 4 | 24.3 s → 0.14 s |
| `GET /admin/stats` | 17,003 → 6 | 30.2 s → 0.16 s |
| `GET /admin/talks.csv` | 21,002 → 5 | 36.9 s → 0.19 s |

- Bodies byte-identical (1.8 MB list, 572 KB CSV; stats apart from its `generated` timestamp).
- `./mvnw package` 154/154 on Java 25; new `TalkListBatchTest` (0/1/3/4 ratings, 4.25 → 4.3 rounding, sort ties,
  co-speakers out of id order, orphan speaker; every list item must equal `GET /talks/{id}`).
- Black-box 110/110 and Hurl 7 files / 171 requests against the 7,000-talk database (the two timeouts are gone).

### Review

Fresh read-only reviewer, verdict **ready**. Triage:

| # | Finding | Severity | Verdict | What we did, or why not |
|---|---|---|---|---|
| 1 | Co-speaker order test may not catch a dropped `ORDER BY id` (heap order = id order on a fresh table) | nit | agree, deferred | The SQL orders explicitly; not worth a round while lists are slow in production |
| 2 | Empty speaker list (`/speakers/{noTalks}/talks` → `[]`) not asserted here | nit | agree, deferred | Covered by the short-circuit in code; add with the next change to this test |
| 3 | Branch behind main; carries the spring-boot-4 post-deploy evidence commit | nit | done | `main` merged in (clean); the evidence commit (`ef433c4`) is intentional |

### After merge (a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] `GET /talks` and `/admin/stats` on TEAM_URL answer in well under a second
- [ ] black-box suite against TEAM_URL 110/110
- [ ] harness: every claimed feature still all OK

No claim: this protects claimed features (talks, ratings, acceptance) rather than adding one.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
