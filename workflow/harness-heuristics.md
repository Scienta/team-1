# What the harness probes, and what it expects

Built from the FAULTY transcripts, the check timelines and the production request logs (user agent
`Java-http-client/...`). Incidents (#n) are in `workflow/retro.md`. Each item is marked
**[evidence]**, meaning a check passed or failed on it, or **[inference]**, meaning our best reading but not
confirmed. A PO answer that contradicts an item needs a person to decide; the agents flag it (see
`.claude/agents/spec-writer.md`). For black-box coverage of the HTTP surface, follow
`.claude/rules/black-box-api.md`. This file says *what* to cover.

## Status codes

- **H1 Unknown id in the path: 404.** [evidence] It uses huge random ids (`/talks/2080227655/status`,
  `/workshops/2077445881/registrations`, `/talks/8042/co-speakers/2073688900`) and accepts our 404.
- **H2 Unknown id in the body: 400, not 404.** [evidence + inference] `POST /talks` with
  `"speakerId": 2035689785` gets our 400, and talks checks have been green since tick 0. `PUT /programme/R3/S1`
  with `"talkId": 2074160843` got our 404 and scheduling/check-1 is FAULTY (#7). That 400 fixes #7 is an inference.
- **H3 A resource that exists but a rule refuses: 409; reading it is 200.** [evidence] Registering for an
  accepted *presentation*: 404 failed, 409 passes. Listing its registrations: 404 failed, 200 `[]` passes (#5).
  "Not a workshop" is a rule, not "not found".
- **H4 Invalid input: 400.** [evidence] Probed with: field missing, `null`, whitespace only (`" \t "`),
  wrong-case enums (`"intermediate"`, `"Intermediate"`), negative ids (`/ratings/-535`). All our 400s pass.
- **H5 Path tokens outside the declared enum: 404.** [evidence] `R4`, `R0`, `S0`, `S6`, `s1`, `r1`, `Room1`,
  `slot2` got our 404, and scheduling/check-1 carried on past them.
- **H6 Use the status and body the API block declares.** [evidence for 201/200; inference for 204] Creates
  are 201 and pass. Scheduling `delete` declares no response; our 200 + body is FAULTY on every check that
  deletes an occupied cell (#6), so no declared body most likely means **204**.
- **H7 The main speaker as their own co-speaker: 400. A third co-speaker: 409.** [evidence, #2] An input
  that can never be valid is 400. A limit that depends on current state is 409.

## Repeats, undo and state machines

- **H8 Removing what isn't there: 404.** [evidence, #1] `DELETE` of a non-co-speaker, or the same `DELETE`
  twice: 200 failed, 404 passes.
- **H9 Repeating a transition into the same state: 409 when the request says "final", "once" or "only".**
  [evidence, #3, #4] `ACCEPTED`→`ACCEPTED`, `REJECTED`→`REJECTED` and `WAITLISTED`→`WAITLISTED` all
  had to be 409. The check **picks the repeated value at random**, so a partial fix fails intermittently.
  Exception: the request itself says "changes nothing" (adding an existing co-speaker: 200, passes).
- **H10 After a refused write it reads the state back.** [evidence] Logs: `POST /workshops/8000/registrations`
  → 409, then `GET` of the same id → 200 `[]`. The read must succeed and show nothing changed.
- **H11 A check stops at its first wrong answer.** [evidence] Workshop check-2 failed on POST, then on
  the next request (GET) once POST was fixed. Fix every sibling case of the rule in one go.

## Concurrency

- **H12 Parallel bursts on one resource; exactly one (or exactly N) succeed.** [evidence, logs 14:44–14:48 UTC]
  - 10+ parallel `POST /talks` for one speaker: exactly 3 × 201, the rest 409.
  - 5–7 parallel `PUT /talks/{id}/status` with mixed values, *identical ones included*: exactly one 200,
    the rest 409 (#3).
  - 3 parallel `PUT /programme/R1/S2` with different talks: one 200, two 409.
  - Workshop rush: dozens in parallel; exactly 20 × 201, then 409 "full".
  - 14 parallel `PUT /talks/{id}/ratings/{reviewer}` (same reviewer): all 200 (a replace, not a limit).
- **H13 No 5xx under its load.** [evidence] None in the logs all day. A unique-index race must map to 409
  (speakers #9 came from our own review, not the harness).

## Formats and leftovers

- **H14 Emails differ in case and whitespace.** [evidence] `X-3E03C57F9A27@EXAMPLE.TEST` as a duplicate
  gets 409. Whitespace-only email gets 400.
- **H15 Trailing slashes.** [evidence] `POST /speakers/` (speakers/check-4) and `GET /speakers/{id}/` work
  like the slash-free forms.
- **H16 State left over from earlier runs.** [evidence] Checks start by cleaning up (`DELETE /programme/R1/S3`
  on a cell an earlier run filled), and the database keeps every tick's data. The cleanup request is judged
  like any other (#6).

## Timing and visibility

- **H17 Each tick's checks run in a burst of about 10–15 s, about a minute apart, with a timeout of a few seconds.**
  [inference from logs] At tick 194 the harness recorded no response to a request our app answered in 18 ms,
  4.7 s after the previous one (#9).
- **H18 A deploy during the burst fails every check.** [evidence, #8] One merge at a time; check `/health`
  before merging the next one.
- **H19 Released features are probed before you claim, but their checks are hidden.** [evidence]
  Co-speaker requests appear from 11:43 UTC, before the claim at tick 67. `/feedback` lists unclaimed
  features with no checks. So the logs show *what* it sends; compare our answers with H1–H16 yourself.
- **H20 Reading the logs.** [evidence] An error line `GET <path> -> 4xx error` is the error dispatch of the
  line before it, whatever its real method. A FAULTY transcript ends at the failing request, and
  `failure` gives only the method and the templated path.
