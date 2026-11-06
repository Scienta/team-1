## Acceptance state machine (`acceptance`)

Feature request: `specs/acceptance/request.md` · Our spec: `specs/acceptance/spec.md` (PO confirmed)

### What changed

- `PUT /talks/{id}/status` (`ACCEPTED` / `REJECTED` / `WAITLISTED`) and `POST /talks/{id}/withdraw` (`{"speakerId": n}`),
  both answering the Talk. `ConferenceService.decide` / `withdraw`: lock the talk row (`SELECT … FOR UPDATE`), check in
  the spec's D12 order (404 talk → 400 body → 403 who → 409 state/ratings), then a conditional
  `UPDATE talk SET status = ? WHERE id = ? AND status = ?` (`TalkDao.updateStatusIf`).
- No schema change (`talk.status` already exists). `openapi.yaml` documents both paths and the `Status` enum.
- Separate commit 14ee2c5: the 3-talk limit is now `cfp.talks.max-per-speaker` (default 3, env
  `CFP_TALKS_MAX_PER_SPEAKER`). Unchanged behaviour at the default. This is why `ConferenceServiceTest` changed
  (constructor argument + one new test), although the spec says no existing test should change; no assertion weakened.
- Tests: `AcceptanceTest` (30, Testcontainers), `AcceptanceServiceTest` (1, mock), `examples/acceptance.hurl`,
  and `black-box-tests/src/suites/acceptance.test.ts` (53, Vitest) per `.claude/rules/black-box-api.md`.

### Rules and decisions, and the tests that prove them

All in `AcceptanceTest` unless noted; the Vitest suite covers the same rows black-box.

| Id | Rule or decision | Test |
|---|---|---|
| R1, R2 | decide on SUBMITTED/WAITLISTED; ACCEPTED/REJECTED final | `r1r2_submittedTalkCanBeAcceptedRejectedOrWaitlisted`, `r2_waitlistedTalkCanStillBeAcceptedOrRejected`, `r2d4_acceptedIsFinal`, `r2d4_rejectedIsFinal` |
| R3, D5 | accept needs ≥2 distinct reviewers, else 409 | `r3d5_acceptNeedsTwoDifferentReviewers`, `r3d5_waitlistedTalkAlsoNeedsTwoReviewersToBeAccepted`, `r3_rejectingAndWaitlistingNeedNoRatings` |
| R4, D11 | main speaker withdraws from SUBMITTED/WAITLISTED/ACCEPTED, not REJECTED | `r4_mainSpeakerCanWithdrawFromSubmittedWaitlistedOrAccepted`, `r4d11_cannotWithdrawARejectedTalk` |
| R5, D9, D10 | anyone else (incl. co-speaker, unregistered id) gets 403 | `r5d9_anotherSpeakerCannotWithdraw`, `r5d9_aCoSpeakerCannotWithdraw`, `r5d10_anUnregisteredSpeakerCannotWithdraw` |
| R6 | WITHDRAWN is final | `r6d11_withdrawingTwiceIs409`, `r6d4_noDecisionOnAWithdrawnTalk`, `d11d12_aStrangerGets403WhateverTheState` |
| R7 | status in every Talk response | `r7_statusShowsInEveryTalkResponse` |
| R8 | a refusal changes nothing | every refusal test re-reads the talk |
| R9 | concurrent decisions: exactly one wins | `r9_concurrentRejectAndAcceptOnAWaitlistedTalk_exactlyOneWins`, `r9_concurrentWithdrawAndRejectNeverBothSucceed` |
| D1, D12 | unknown talk 404, even with a bad body | `d1_unknownTalkIs404`, `d12_unknownTalkIs404EvenWithABadBody`, `d12_badBodyBeats403And409` |
| D2, D3, D8 | invalid status / body / speakerId → 400 | `d2_statusMustBeOneOfTheThreeDecisions`, `d2_aBadStatusOnAFinalTalkIs400Not409`, `d3_missingOrUnreadableBodyIs400`, `d8_speakerIdMustBeAnInteger` |
| D6, D7 | repeating the same decision is 200, unchanged (PO confirmed) | `d6_waitlistingAWaitlistedTalkIs200AndUnchanged`, `d7_repeatingAFinalDecisionIs200AndUnchanged`, `AcceptanceServiceTest.d7_acceptingAnAcceptedTalkAgainSkipsTheRatingsCheck` |
| D13 | rejected/withdrawn talks still count towards the limit (PO confirmed) | `d13_withdrawnAndRejectedTalksStillCountTowardsTheLimit` |
| D14 | `/admin/stats` shows the new statuses | `d14_adminStatsCountTheNewStatuses`, `ratingsKeepWorkingInAnyStatus` |

`./mvnw package`: 85 tests, all green (run by the implementer, the reviewer and the main session).
Black box: `examples/acceptance.hurl` green against a local run; Vitest suite 88 tests green against a local jar
(run in a `node:22` container: Node isn't installed on the machine; the `black-box` workflow on this PR is the first
run with a real Node setup).

### Review

Reviewer verdict: ready, no blockers. Findings and what we did (`specs/acceptance/review.md`):

| # | Finding | Verdict | What we did, or why not |
|---|---|---|---|
| 1 | `ConferenceServiceTest` changed though the spec says no existing test should | agree, no change | from the separate limit refactor (14ee2c5); no assertion weakened; noted above |
| 2 | Hurl missed WAITLISTED → withdraw, WITHDRAWN → REJECTED, withdraw with no body | agree, fixed | three cases added |
| 3 | D7 "no ratings check for an already accepted talk" untested | agree, fixed | mock unit test `AcceptanceServiceTest` |
| 4 | new team rule: Vitest black-box coverage | agree, done | `black-box-tests/src/suites/acceptance.test.ts`, `decisions.md` |

Choices the spec didn't settle, for a person to confirm: a `speakerId` sent as `"5"` or `5.0` is 400; the two new
endpoints parse their body with a plain JSON mapper, so a body with comments is 400 there (the rest of the app allows them).

### Open questions for the product owner

- Waitlisting twice: idempotent (answered)
- Co-speaker can't withdraw: "it's in the requirements", only the main speaker (answered)
- Repeating a final decision: idempotent (answered)
- 3-talk limit counts every talk, whatever its status (answered)

### Before claiming (after merge, a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] `examples/acceptance.hurl` green against TEAM_URL
- [ ] no 5xx in `.github/fagdag/logs.sh`
- [ ] harness: every claimed feature still all OK
- [ ] a person claims; claim-log row added to `REVIEW.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
