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

Trailing slash: `POST /speakers/` and `GET /speakers/{id}/` work like the slash-free forms.
