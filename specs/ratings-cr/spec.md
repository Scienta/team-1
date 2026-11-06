# Ratings change request (trimmed, 2-decimal average): our spec

Feature request: `specs/ratings-cr/request.md` (harness slug `ratings-cr`, V 2, released at tick 167).
Changes the claimed base feature Ratings (`specs/ratings/spec.md`).
Status: PO confirmed (question 1 answered)

## Rules (from the feature request)

- R1 With **5 or more** ratings on a talk, `averageRating` leaves out exactly **one** highest and exactly
  **one** lowest score, even when several reviewers gave the same top or bottom score.
- R2 With fewer than 5 ratings (1-4), every score counts, as before. 0 ratings: `averageRating` is `null`, as before.
- R3 The average is rounded to **2 decimals**, ties at the third decimal to the even neighbour (banker's
  rounding, `HALF_EVEN`): `3.125` -> `3.12`, `3.375` -> `3.38`.
- R4 `ratingCount` still counts every reviewer, including the two left out of the average.
- R5 `GET /talks?sort=rating` ranks by the new, rounded average; the rest of the ranking is unchanged: same
  rounded average -> more ratings first -> older (lower id) first; unrated talks last.
- R6 Everything else about ratings stays as it is (scores 1-5, one rating per reviewer per talk, upsert,
  `ratedAtDisplay`, validation and status codes, concurrency guarantees).
- R7 The new average shows wherever a talk is shown (ratings spec: "wherever a talk is shown"):
  `GET /talks/{id}`, `GET /talks` (any `speakerId`/`sort`), `GET /speakers/{id}/talks`, and any other
  response embedding a `Talk`.

## API

No new endpoints, no new fields, no schema change.

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/talks/{id}`, `/talks`, `/talks?sort=rating`, `/speakers/{id}/talks` | - | 200, `averageRating` per R1-R3 |

Changed field: `Talk.averageRating` (JSON number or `null`), now trimmed at 5+ ratings and rounded to 2 decimals.

## Decisions (where the request is silent)

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | How to compute without float surprises | Integers from SQL: `cnt`, `total = SUM(score)`, `lo = MIN(score)`, `hi = MAX(score)`. If `cnt >= 5`: `keptTotal = total - hi - lo`, `keptCount = cnt - 2`; else `keptTotal = total`, `keptCount = cnt`. Then `BigDecimal.valueOf(keptTotal).divide(BigDecimal.valueOf(keptCount), 2, RoundingMode.HALF_EVEN).doubleValue()` | Exact rational division of two integers, rounded once; no `AVG()`/double arithmetic before rounding (`3.125` as a double sum could round the wrong way). Same pattern as today's `calculateAverage` |
| D2 | Single-talk vs list path must agree | `RatingDao.stats(talkId)` and `RatingDao.statsByTalk(ids)` both return `cnt, total, lo, hi` (one `GROUP BY` query for the list, keeps the batching from talk-list-performance); the trim + rounding lives in **one** Java method (`addRatings`/`calculateAverage`) used by both | Equal by construction; `TalkListBatchTest` already asserts every list item equals its `GET /talks/{id}` |
| D3 | Dropping "one highest and one lowest" with ties | Subtracting `MAX` and `MIN` once each is exactly "drop one of each": `5,5,5,1,1` -> `5+5+1 = 11 / 3 = 3.67` | Request: "exactly one of each, even when several reviewers gave the same score" |
| D4 | All scores equal at 5+ (`3,3,3,3,3`) | `hi = lo = 3`, drop two 3s -> `9/3 = 3.0` | R1 applies regardless; result equals the plain mean anyway |
| D5 | JSON form | Keep `averageRating` a `Double`: Jackson writes the shortest form, so `4.5` (not `4.50`), `4.0`, `4.33`, `3.12` | JSON numbers have no meaningful trailing zeros; the harness compares numbers (`4.5 == 4.50`). Base tests/examples assert `4.5`, `4.0`, `5`, and `"averageRating":4.5,` in the body (`UpgradeInvariantsTest`); a `BigDecimal` field would print `4.50`/`4.00` and change the CSV. The double from a 2-decimal `BigDecimal` prints back as those 2 decimals |
| D6 | Ranking ties | Compare the rounded doubles (`Double.compare`), as today; equal 2-decimal values give identical doubles, so tie-breaks (count desc, id asc, unrated last) apply exactly as before | R5; ranking "uses the new, rounded average" |
| D7 | Re-rating (PUT upsert by the same reviewer) | No special case: the stats are recomputed from the current rows, so a re-rate can change which score is min/max; `ratingCount` unchanged | Upsert already replaces the row (R6) |
| D8 | `/admin/stats` `averageRating` | Mean of the rated talks' `averageRating` values (the new talk averages), **rounded to 2 decimals HALF_EVEN** (BigDecimal, like the talk average), replacing the 1-decimal `Utils.roundRating` cut-off here; `null` when no talk is rated, as today | **PO confirmed** (question 1): same rounding as the talk average |
| D9 | `/admin/talks.csv` rating column | Unchanged code: `String.valueOf(averageRating)` with a decimal comma, so it now shows up to 2 decimals (`4,33`, `4,5`, `4,0`) | Same value as the JSON, same formatting rule as before |
| D10 | Acceptance, scheduling/programme, workshop registration | Not affected: acceptance counts distinct reviewers (not the average); scheduling and workshops don't read ratings | Checked in `ConferenceService` (only `listTalks` sort, `addRatings`, admin stats read `averageRating`) |

### Worked examples (single path and list path must both give these)

| Scores | cnt | Kept | Exact | Shown | Before (1 dp, half up) |
|---|---|---|---|---|---|
| `5` | 1 | all | 5 | `5.0` (JSON `5.0`) | `5.0` |
| `4,4,4,5` | 4 | all | 4.25 | `4.25` | `4.3` |
| `4,4,4,1` | 4 | all | 3.25 | `3.25` | `3.3` |
| `4,4,5` | 3 | all | 4.333... | `4.33` | `4.3` |
| `4,5,5` | 3 | all | 4.666... | `4.67` | `4.7` |
| `4,5` | 2 | all | 4.5 | `4.5` | `4.5` |
| `1,4,4,4,5` (exactly 5) | 5 | `4,4,4` | 4.0 | `4.0` | `3.6` |
| `5,5,5,1,1` | 5 | `5,5,1` | 3.666... | `3.67` | `3.4` |
| `3,3,3,3,3` | 5 | `3,3,3` | 3.0 | `3.0` | `3.0` |
| `1,5,5,5` then a 5th rating `5` | 4 -> 5 | all -> `5,5,5` | 4.0 -> 5.0 | `4.0` -> `5.0` | `4.0` -> `4.2` |
| `1,2,3,4,5`, reviewer of `1` re-rates to `5` | 5 -> 5 | `2,3,4` -> `3,4,5` | 3.0 -> 4.0 | `3.0` -> `4.0` | `3.0` -> `3.8` |
| `5,4,4,4,4,4` | 6 | `4,4,4,4` | 4.0 | `4.0` | `4.2` |
| `1,1,3,3,3,3,4,4,4,5` | 10 | 8 scores, sum 25 | 3.125 | `3.12` (half up would be 3.13) | `3.1` |
| `1,3,3,3,3,3,4,4,4,5` | 10 | 8 scores, sum 27 | 3.375 | `3.38` | `3.3` |
| reviewers 1-20, score `r % 5 + 1` | 20 | 18 scores, sum 54 | 3.0 | `3.0` | `3.0` |

A tie at the third decimal needs `keptCount` divisible by 8 (so at least 10 ratings); with 1-4 ratings
(denominators 1-4) no rounding tie can occur at 2 decimals, so HALF_EVEN only matters at 10+ ratings.

## Concurrency

Nothing new. The average is computed on read from the current rows; the upsert (`ON CONFLICT DO UPDATE`)
already keeps one row per reviewer under parallel and double-clicked PUTs. No new invariant to lock.

## Changes to existing behaviour

The Ratings response changes (claimed base feature). Tests that assert 1-decimal half-up or the old ranking
and must change:

- `src/test/java/no/scienta/cfp/RatingsTest.java`
  - `theAverageIsRoundedHalfUpToOneDecimal`: `4,4,4,5` now `"4.25"` (rename the test).
  - `theRankingIsByRoundedAverageThenRatingCountThenAgeWithUnratedLast`: 4.33 now ranks above 4.25:
    `fourAndAHalf, fourPointThreeThree, fourPointTwoFive, fourTwice, fourTwiceLater, fourOnce, unrated`.
  - `manyReviewersAtOnceAreAllCounted` (20 ratings, 3.0) and the other 3.0/5.0 asserts: values unchanged.
- `src/test/java/no/scienta/cfp/TalkListBatchTest.java`: `three` 4.3 -> 4.33, `halfUp` 4.3 -> 4.25; ranking
  becomes `tieMany, tieFew, three, halfUp, one, tieSameA, tieSameB, unrated`; admin-stats `mean` literals
  become `(4.0 + 4.33 + 4.25 + 4.5 + 4.5 + 3.0 + 3.0) / 7` (= 3.94 -> 3.9); CSV row `;4,3;3` -> `;4,33;3`;
  fix the comments (`-> 4.3`, "halfUp").
- `black-box-tests/src/suites/ratings.test.ts`: "rounds average to one decimal (half up)": `4.3` -> `4.25`,
  `3.3` -> `3.25` (rename). Other asserts (5, 4, ranking of single ratings) unchanged.
- `src/test/java/no/scienta/cfp/UpgradeInvariantsTest.java` `numberTypesAreUnchanged`: `"averageRating":4.5,`
  still holds; update the comment "with one decimal". `specs/spring-boot-4/spec.md` R7 ("rounded to one
  decimal") is superseded by this CR.
- `examples/ratings.hurl`: `4.5` still holds; fix the comment "rounded to 1 decimal". `examples/spring-boot-4.hurl`
  `4.0` unchanged.
- `ConferenceService.calculateAverage` comment (1 decimal half up) and `UtilsTest` comment referencing it.
- `UtilsTest.roundRatingCutsOffForTheAdminStatistics`: unchanged (D8).
- New tests needed (none of the existing ones has 5+ ratings with distinct min/max): every row of the
  worked-examples table, on `GET /talks/{id}` **and** the list paths, plus a ranking where trimming flips the
  order (e.g. `1,5,5,5,5` = 5.0 above `4,5,5,5` = 4.75 with fewer ratings).

### Harness risk

- On the harness the claimed `ratings` feature's checks 2 and 4 were retired when this CR came out
  (tick ~167); checks 1, 3 and 5 still run against the deployed service. They must stay green after deploy.
- Deploying the new average changes a claimed feature's behaviour: deploy and the person's claim of
  `ratings-cr` should happen together (claim right after the deploy is verified), and after deploy check the
  `ratings` and `ratings-cr` feedback for FAULTY checks.

## Assumptions

- The threshold counts ratings (= distinct reviewers, = `ratingCount`), so `cnt >= 5` in SQL is right.
- The harness compares `averageRating` as a JSON number (`4.5` equals `4.50`), not as text.
- `/admin/stats` and the CSV aren't checked by the harness against the CR (they aren't in the base specs).

## Open questions for the product owner

1. (Low risk) Should `/admin/stats` `averageRating` (today: mean of talk averages, cut off to 1 decimal)
   also move to 2 decimals / banker's rounding, or stay as it is? We keep it as it is (D8).
   **Answered (PO, relayed by a team member, 2026-11-06): yes, 2 decimals with banker's rounding.** D8 updated.
