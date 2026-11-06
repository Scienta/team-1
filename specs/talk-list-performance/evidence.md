# talk-list-performance: evidence

**Problem.** On production (~6,500 talks) `GET /talks` took 25 s and `GET /admin/stats` 35 s. Each talk in a list
ran two queries (`RatingDao.stats`, `CoSpeakerDao.findByTalk`); stats and the CSV also looked each talk's speaker up.

**Change.** Lists load ratings (`SELECT talk_id, COUNT(*), SUM(score) ... WHERE talk_id = ANY(?) GROUP BY talk_id`)
and co-speakers (`... ORDER BY talk_id, id`) for all their talks in one query each and fill the DTOs in memory
(`ConferenceService.enrichAll`). Stats and `/admin/talks.csv` read all speaker names in one query
(`SpeakerDao.namesById`); a talk whose speaker row is gone still makes the CSV 404, as `getSpeaker` did.
`GET /talks/{id}` and the write paths still use the per-talk queries. No schema change: `EXPLAIN` shows the
batch queries use `rating_pkey (talk_id, reviewer_id)` and `co_speaker (talk_id, speaker_id)`.

## 1. Tests

`TalkListBatchTest` (green on main before the change, so it pins the old behaviour): talks with 0, 1, 3, 4 ratings,
4.25 -> 4.3 half up, ties on average (count desc) and on average and count (id asc), 0/1/2 co-speakers added
out of id order. Every talk in `GET /talks`, `?sort=rating`, `?speakerId=`, `/speakers/{id}/talks` must equal its
`GET /talks/{id}`; the sort=rating order, `/admin/stats` numbers and CSV rows are checked; then a talk with a
missing speaker: `speakersWithTalks` doesn't count it, CSV 404.
`./mvnw -B package`: 154 tests, 0 failures.

## 2. Before/after, same local database

Postgres 16 in a throwaway container (port 25432, `log_statement=all` to count queries), seeded with 3,000 speakers,
7,000 talks, 21,000 ratings, 2,100 co-speakers (700 talks with 1, 700 with 2, added out of id order).
Main's jar (`origin/main`, ef433c4) and this branch's jar against it, one warm-up request, then one timed request.
Queries count statements the server logged for the request, including Hikari's connection check.

| Endpoint | Queries before | Queries after | Time before | Time after |
|---|---:|---:|---:|---:|
| `GET /talks` | 14,002 | 4 | 24.6 s | 0.21 s |
| `GET /talks?sort=rating` | 14,005 | 4 | 24.3 s | 0.14 s |
| `GET /admin/stats` | 17,003 | 6 | 30.2 s | 0.16 s |
| `GET /admin/talks.csv` | 21,002 | 5 | 36.9 s | 0.19 s |
| `GET /speakers/42/talks` (3 talks) | 9 | 5 | 0.029 s | 0.023 s |
| `GET /talks?speakerId=42` (3 talks) | 8 | 4 | 0.026 s | 0.020 s |

Response bodies before and after: byte-identical for all six (`/talks` 1,833,502 bytes, CSV 572,151 bytes), and for
`/admin/stats` identical apart from the `generated` timestamp.

## 3. Black box

Against this branch's jar on the 7,000-talk database: `black-box-tests` (`CFP_BASE_URL=http://localhost:18081
npx vitest run`) 110/110 passed; `examples/run.sh` 7/7 files, 171 requests passed.
