# Black-box decisions

Harness base specs leave status codes and some edges open. These are the behaviours this suite asserts (aligned with the running Java app).

| Situation | Status |
|-----------|--------|
| Speaker / talk not found | `404` |
| Invalid / missing speaker fields, invalid email, bio > 500 | `400` |
| Duplicate email (any case) | `409` |
| Invalid talk format/level/title/speakerId | `400` |
| Unknown speakerId on submit | `400` |
| 4th talk for a speaker | `409` |
| Invalid rating score / non-positive reviewerId | `400` |
| Unknown talk on rate | `404` |
| Invalid `sort` query (not `rating`) | `400` |
| Health DB up | `200` `{ status: "ok", version }` |
| Health DB down | `503` `{ status: "db-unavailable", version }` (not exercised in CI happy path) |
| Third co-speaker | `409` |
| Main speaker as own co-speaker | `400` (not 409 — harness check-2) |
| Re-add existing co-speaker | `200`, list unchanged |
| Remove non-co-speaker / remove twice | `404` (not 200 — harness check-1) |
| Unknown talk or speaker on co-speaker routes | `404` |
| Acceptance: unknown talk on `PUT /talks/{id}/status` or `POST /talks/{id}/withdraw` (even with a bad body) | `404` |
| Acceptance: `status` missing, null, not a string, unknown, not all caps, or `SUBMITTED`/`WITHDRAWN` | `400` |
| Acceptance: body missing, not JSON, or not a JSON object | `400` |
| Acceptance: decision not allowed from the current status (ACCEPTED/REJECTED/WITHDRAWN are final) | `409` |
| Acceptance: `ACCEPTED` with fewer than 2 distinct reviewers (a reviewer rating twice counts once) | `409` |
| Acceptance: `WAITLISTED` on a waitlisted talk | `409`, unchanged (not 200 — harness acceptance/check-3) |
| Acceptance: repeating a final decision (`ACCEPTED` again, `REJECTED` again) | `409`, unchanged (not 200 — harness acceptance/check-3, check-5) |
| Acceptance: withdraw `speakerId` missing, null or not a whole number (string, decimal, boolean) | `400` |
| Acceptance: withdraw by anyone but the main speaker (another speaker, a co-speaker, an unregistered id) | `403`, whatever the talk's status |
| Acceptance: withdraw a `REJECTED` or `WITHDRAWN` talk (by the main speaker) | `409` |
| Acceptance: order of checks | `404` talk, then `400` body, then `403` who, then `409` state/ratings |
| Acceptance: withdrawn and rejected talks still count towards the 3-talk limit | 4th talk `409` (PO confirmed) |
| Workshops: unknown talk, or a talk whose format isn't `WORKSHOP`, on `POST`/`GET /workshops/{id}/registrations` | `404` (PO confirmed) |
| Workshops: non-integer id | `400` |
| Workshops: body missing, not JSON or not an object; `name`/`email` missing, null, blank or not a string; invalid email | `400` |
| Workshops: registering for a workshop that isn't `ACCEPTED` (submitted, waitlisted, rejected, withdrawn) | `409` (PO confirmed) |
| Workshops: same email again for the same workshop (case-insensitive, surrounding whitespace incl. tabs/line breaks ignored) | `409` (PO confirmed) |
| Workshops: 21st registration, also in a parallel rush | `409`; exactly 20 stored |
| Workshops: listing a workshop in any status (withdrawn keeps its list) | `200` (PO confirmed) |
| Workshops: registering for a talk that isn't a `WORKSHOP` | `409` (not 404 — harness check-2, tick 162); listing it is `200 []` (not 404 — check-2, tick 174) |
| Workshops: order of checks | `404` unknown talk, then `400` body, then `409` (not a workshop, not accepted, duplicate, full) |
| Workshops: `DELETE /workshops/{id}/registrations` | `405` |
| Scheduling: unknown / wrong-case room or slot | `404` |
| Scheduling: `GET` or `DELETE` empty cell | `404` |
| Scheduling: `PUT` body missing / `talkId` missing, null, or not an integer | `400` |
| Scheduling: unknown `talkId` | `404` |
| Scheduling: talk not `ACCEPTED` | `409` |
| Scheduling: workshop outside `S4` | `409` |
| Scheduling: cell occupied by another talk | `409` |
| Scheduling: talk already elsewhere on the programme | `409` (DELETE first) |
| Scheduling: same talk into the same cell again | `200`, unchanged (idempotent) |
| Scheduling: speaker or co-speaker already in that slot (any room) | `409` |
| Scheduling: successful `DELETE` | `200` with removed `Entry` (`room`, `slot`, `start`, `end`, `talkId`) |
| Scheduling: order of `PUT` checks | `404` room/slot → `400` body → `404` talk → `409` status → fit → already-elsewhere → occupied → speaker clash |
| Scheduling: withdraw / late co-speakers after place | programme `Entry` stays until `DELETE` |

Trailing slash: `POST /speakers/`, `GET /speakers/{id}/`, and `/programme/{room}/{slot}/` work like the slash-free forms.
