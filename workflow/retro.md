# Retro: harness checks that failed after we claimed

Sources: harness `/feedback` and `/feedback/timeline` at tick 201–203, production request logs (Loki),
`git log origin/main`. Score at tick 201: total 1132.4, earned 1236.13, **bled −103.73**.
The harness doesn't split bleed per feature. The "≈ bled" column is an estimate: 2·V/N points per
FAULTY check per tick (V = feature value, N = its number of checks). That rate is calibrated on the
co-speakers figure in `REVIEW.md` (−10.67 by tick 75).

## Incidents

| # | Feature (V) | Check | Claimed | FAULTY ticks | ≈ bled | We answered | Harness wanted | Root cause | Step that should have caught it |
|---|---|---|---|---|---|---|---|---|---|
| 1 | co-speakers (1) | check-1 | 67 | 68–81 (14) | 9.3 | `DELETE` of a non-co-speaker: 200, "changes nothing" | 404 (passes since `2f20777`) | status-code guess. An earlier draft spec (stash `788f5dc`) listed it as an open PO question, but that draft was lost when two people built the feature in parallel | 3 spec gate; 9 verify |
| 2 | co-speakers (1) | check-2 | 67 | 68–81 (14) | 9.3 | main speaker as own co-speaker: 409 | 400 (passes) | the lost draft had 400; the implementation that shipped (`6319fc6`) chose 409 | 2 spec; 5 review |
| 3 | acceptance (2) | check-5 | 140 | 141–151 (11) | 8.8 | `REJECTED` on a rejected talk: 200 (D7, relayed PO answer "idempotent") | 409. In parallel bursts, exactly one 200 | a relayed PO answer loosened the request's "the committee's decision is final" | 2 spec (contradicts the request); 5 review check 2 |
| 4 | acceptance (2) | check-3 | 140 | 142–173, on and off (11) | 8.8 | repeating `ACCEPTED`, then (after `297f7d6`) `WAITLISTED`: 200 (D6/D7) | 409 for any repeat of the same decision (`adf8f15`) | the check repeats a randomly chosen decision; the first fix covered only the cases seen, not their siblings | 6 triage of the fix |
| 5 | workshop-registration (2) | check-2 | 161 | 162–171 on POST, 172–180 on GET (19) | 19 | registering for an accepted presentation: 404 (D2, "PO confirmed"); then listing it: 404 | POST 409 (`500faa6`), GET 200 `[]` (`a2d0e47`): the talk exists, so it isn't "not found" | a relayed PO answer; and POST and GET on one resource were decided separately. The harness reads the list back after a refused write | 2 spec; 9 verify |
| 6 | scheduling (3) | check-3, 4, 6 | 198 | 199–203+ (open) | ≈1/check/tick | `DELETE /programme/{r}/{s}`: 200 + removed Entry (D12, PO question 2 never answered) | *inference:* 204, since the request declares no response for `delete` | invented a response body the API doesn't declare | 2 spec; 8 pre-claim probes |
| 7 | scheduling (3) | check-1 | 198 | 199–201+ (open) | ≈1/tick | `PUT` with an unknown `talkId` in the body: 404 (D4) | *inference:* 400, like an unknown `speakerId` in `POST /talks` (green since tick 0) | an unknown id in the body was treated like a path resource | 2 spec |
| 8 | all claimed | various | — | 197 (2x tick) | n/a | no answer: three merges in 2 minutes (`6b88f4e`, `65321f1`, `9a5d1fd`); no requests logged 14:43:08–14:43:58 UTC while the instance was replaced | an answer | deploying during a check burst at double weight | 9 / merge timing |
| 9 | talks, acceptance (2x) | talks/check-2, acceptance/check-4, 5 | — | 194 | n/a | harness saw no response for `PUT /talks/7784/status`; our log shows it answered in 18 ms, 4.7 s after the previous request | an answer in time | *unexplained:* no 5xx and p95 < 310 ms in our logs. Likely queueing before the app under double load | — |

Not harness failures:
- **speakers #9**: parallel `PUT /speakers/{id}` to one email gave 500. Our concurrency review found it,
  and `65321f1` fixed it. The harness never sent `PUT /speakers` (none in the logs), and there was **no 5xx
  in the logs all day**.
- **spring-boot-4** (claimed 184, both checks OK): the review widened the trailing-slash fix to `/**`,
  and the harness does send `POST /speakers/` and `GET /speakers/{id}/`. The PO's late demand for byte-identical error
  bodies (R12a) held, with no FAULTY.

## Hypotheses, tested

| Hypothesis | Verdict | Evidence |
|---|---|---|
| Status-code guesses where the request is silent | **confirmed** | #1, #2, #6, #7 |
| Relayed "PO confirmed" answers were wrong vs the harness | **confirmed, 3 of 9** | D6, D7 (#3, #4), workshop D2 (#5) wrong. Withdraw by co-speaker, 3-talk limit, D8, D9, D15 held |
| Concurrency means "exactly one wins", not idempotent | **confirmed** | logs: 5–7 parallel mixed decisions on one talk give one 200, the rest 409; 10+ parallel `POST /talks` for one speaker give three 201 |
| POST and GET on one resource must agree | **confirmed** | #5: refused POST, then GET read-back |
| 500s under parallel load | **rejected for the harness** | 0 × 5xx in the logs all day; #9 came from our own review |
| Claimed before the deployed behaviour was checked | **partly** | co-speakers claimed minutes after the deploy. The harness probed the endpoints from 11:43 UTC, but unclaimed checks don't show in `/feedback`, so the logs show *what* it sends, not what it *expects* |

## Lessons

1. **Wording beats relayed answers.** "Final", "only once" and "never" in the request mean a repeat is
   refused (409). Three relayed PO answers that softened this cost ≈ 37 points (#3–#5). If a PO answer
   contradicts the request or `harness-heuristics.md`, flag it and keep the request's reading.
2. **Unanswered PO questions are the riskiest decisions.** Six of the seven status-code failures sat on a
   decision that had been a PO question (#1–#6) or on a guess that was never asked (#7). Before the
   claim, re-check each of them against the heuristics.
3. **404 is for the path only.** An unknown id in the body is 400. An existing resource that a rule
   refuses is 409, and its GET is still 200 (#5, #7; talks base checks).
4. **Don't invent responses.** Use the status and body the API block declares: 201 for creates, and no
   declared body means 204 (#6, inference).
5. **Fix the class, not the request.** A check stops at its first failure, so a one-case fix exposes the
   next case: two rounds each for acceptance and workshop-registration (30 FAULTY ticks).
6. **One merge per deploy, outside the check burst.** Three back-to-back merges at a double-weight tick
   failed every check once (#8).

What changed in the workflow: `workflow/harness-heuristics.md`, used by the spec-writer, implementer and
reviewer, and concrete probes in `/feature` step 8.
