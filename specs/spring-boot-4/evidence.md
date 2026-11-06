# Spring Boot 4 upgrade: D12 evidence

Spec: `specs/spring-boot-4/spec.md` (D12). Collected 2026-11-06 on a developer machine.

## How

- **Before:** the Docker image built from `015861e` (Spring Boot 2.7.18, `eclipse-temurin:11-jre`, the image production
  ran), against a fresh `postgres:16-alpine` database.
- **After:** the Docker image built from this branch (`docker build --platform linux/amd64`; Spring Boot 4.1.2,
  `eclipse-temurin:25-jre`), against a freshly recreated database in the same Postgres container.
- Both run with `PGHOST=<postgres container> PGSSLMODE=disable` on port 8080. The script below ran against each (78
  requests: every base endpoint, an unrated and a rated talk, co-speakers, every error case of R9-R11, `/health`,
  `/actuator/*`, `/admin/*`, `/openapi.yaml`, `/swagger-ui.html`, framework 404/405/415). It prints status,
  Content-Type, redirect and body per request; only `timestamp`, `submittedAt`, `ratedAtDisplay` and `generated`
  values are masked (ids are deterministic on a fresh database; dates are compared as is, same day). Then
  `diff before.txt after.txt`.

## Result

Status codes, Content-Types (incl. `charset=UTF-8` on errors), redirects, field names and order, and every
success body are identical, including `submittedOn`, `averageRating: null`/`4.3`, `bio: null`, `ratedAtDisplay`'s
shape, the trailing-slash URLs, JSON comments, unknown fields, `/admin/stats`, `/admin/talks.csv` (`6. nov. 2026`,
D11), `/openapi.yaml` (same SHA-1) and `/swagger-ui.html` (302 to `/swagger-ui/index.html`).

14 lines differ:

| Request(s) | Difference | Verdict |
|---|---|---|
| `GET /actuator/info` | `11.0.32.1 / 2.7.18 / 5.3.31` -> `25.0.5 / 4.1.2 / 7.0.10` | Expected (R3) |
| `GET /speakers/abc`, `GET /talks/abc`, `PUT /talks/1/ratings/abc` (400) | `message` wording from Spring 7: `Method parameter 'id': Failed to convert ...; For input string: "abc"` instead of `Failed to convert ...; nested exception is java.lang.NumberFormatException: ...` | Not changed, see below |
| Malformed JSON on `POST /speakers`, `POST /talks`, `PUT .../ratings/1`, `"speakerId":"x"` (400) | `message` from Jackson 3 without the `nested exception is ...` / `at [Source: ...]` tail (`JSON parse error: Unexpected end-of-input within/between Object entries`) | Not changed, see below |
| `POST /speakers` with `text/plain` (415), `DELETE /talks` (405), `GET /nope` (404) | `message` wording: `Content-Type '...' is not supported.`, `Method 'DELETE' is not supported.`, `No static resource nope.` | Not changed, see below |
| `@Valid` failures on `POST /speakers` (`{}`, blank name, 501-char bio) (400) | Same `message` and same `errors` content, but each error object's properties come in a different order (`objectName, field, rejectedValue` first), and for `{}` the two errors are listed name-then-email (Hibernate Validator's order isn't defined in either version) | Not changed, see below |

Not changed: these are framework-generated, human-readable texts on rejected garbage input. The status codes and
the `{timestamp, status, error, message, path}` shape with `message` filled in (R9, R12) are identical; reproducing
Spring 5 / Jackson 2 exception texts would mean hand-writing framework internals. No spec rule pins them (D9).

Differences found and fixed during the comparison and the tests (each now pinned by a test):

- `submittedAt` came out as `...Z`, `submittedOn` as a full timestamp: explicit formats on `TalkDto` (D4).
- Property order became alphabetical: `spring.jackson.mapper.sort-properties-alphabetically=false` (D8).
- `POST /speakers/`, `GET /speakers/{id}/` were 404: `UrlHandlerFilter` for `/speakers/**` (D6).
- JSON comments rejected, `message` missing from error bodies: Boot 4 property names
  (`spring.jackson.json.read.allow-java-comments`, `spring.web.error.*`; also `spring.servlet.encoding.force`, the
  old `server.servlet.encoding.force` is an error-level deprecation in Boot 4).
- Error `timestamp` became `...Z`: written as `yyyy-MM-dd'T'HH:mm:ss.SSS+00:00` again (`WebConfig.errorAttributes`).
- Error responses lost `;charset=UTF-8`: UTF-8 forced on the error dispatch too (`WebConfig`).
- `/actuator/health` gained `"groups":["liveness","readiness"]`: `management.endpoint.health.probes.enabled=false`.

Not done here: `examples/run.sh` (Hurl isn't installed on this machine), and the after-deploy check of
`/actuator/info` on `TEAM_URL` and the harness feedback (D12 (2) and (4)).

## The script

```bash
#!/usr/bin/env bash
# D12 before/after script: run against a service on a FRESH database. Prints, per request, the status,
# the Content-Type and the body, normalised (timestamps, dates and the Java version masked).
set -u
B="${1:-http://localhost:8080}"
J='Content-Type: application/json'
r() { # method path [body] [content-type]
  local m="$1" p="$2" d="${3-}" ct="${4:-application/json}" out
  echo "### $m $p ${d:+$d}"
  if [ -n "$d" ]; then
    out=$(curl -s -o /tmp/d12body -w '%{http_code} %{content_type} %{redirect_url}' -X "$m" -H "Content-Type: $ct" --data-binary "$d" "$B$p")
  else
    out=$(curl -s -o /tmp/d12body -w '%{http_code} %{content_type} %{redirect_url}' -X "$m" "$B$p")
  fi
  echo "$out" | sed -E "s#$B##"
  if [ "$p" = "/openapi.yaml" ] || [ "${p%%.html}" != "$p" ]; then
    echo "sha1 $(sha1sum </tmp/d12body | cut -c1-12) bytes $(wc -c </tmp/d12body)"
  else
    sed -E -e 's/"timestamp":"[^"]*"/"timestamp":"<T>"/g' \
      -e 's/"submittedAt":"[^"]*"/"submittedAt":"<AT>"/g' \
      -e 's/"ratedAtDisplay":"[^"]*"/"ratedAtDisplay":"<DISP>"/g' \
      -e 's/"generated":"[^"]*"/"generated":"<GEN>"/g' \
      /tmp/d12body
    echo
  fi
}
r GET /health
r GET /actuator/info
# speakers
r POST /speakers '{"name":"Ada Lovelace","email":"ada@example.com","bio":"Wrote the first program."}'
r POST /speakers '{"name":"Grace Hopper","email":"grace@example.com"}'
r POST /speakers/ '{"name":"Slash Person","email":"slash@example.com"}'
r POST /speakers '{ /* mobile */ "name":"Comment Person", // c
 "email":"comment@example.com"}'
r GET /speakers/1
r GET /speakers/2
r GET /speakers/3/
r GET /speakers/987654321
r GET /speakers/abc
r POST /speakers '{}'
r POST /speakers '{"name":"   ","email":"x@example.com"}'
r POST /speakers '{"name":"Ada","email":"not-an-email"}'
r POST /speakers '{"name":"Dup","email":"ADA@example.com"}'
r POST /speakers "{\"name\":\"Long\",\"email\":\"long@example.com\",\"bio\":\"$(printf 'b%.0s' $(seq 501))\"}"
r POST /speakers '{"name":"Ada",'
r POST /speakers 'name=Ada' 'text/plain'
r PUT /speakers/2 '{"name":"Grace B. Hopper","email":"grace@example.com","bio":"COBOL"}'
r PUT /speakers/987654321 '{"name":"X","email":"x@example.com"}'
r PUT /speakers/2 '{"name":"","email":"grace@example.com"}'
r GET '/speakers/search?q=ada'
r GET "/speakers/search?q=%27%20OR%20%271%27%3D%271"
# talks
r POST /talks '{"title":"Legacy is a feeling","format":"PRESENTATION","level":"ADVANCED","speakerId":1}'
r POST /talks '{"title":"Workshop","format":"WORKSHOP","level":"BEGINNER","speakerId":2,"durationMinutes":999}'
r POST /talks '{"title":"Lightning","format":"LIGHTNING_10","level":"INTERMEDIATE","speakerId":1}'
r POST /talks '{"title":"Lightning 20","format":"LIGHTNING_20","level":"BEGINNER","speakerId":1}'
r POST /talks '{"title":"Fourth","format":"PRESENTATION","level":"BEGINNER","speakerId":1}'
r POST /talks '{"title":"T","format":"presentation","level":"BEGINNER","speakerId":2}'
r POST /talks '{"title":"T","format":"PRESENTATION","level":"EXPERT","speakerId":2}'
r POST /talks '{"title":"  ","format":"PRESENTATION","level":"BEGINNER","speakerId":2}'
r POST /talks '{"title":"T","format":"PRESENTATION","level":"BEGINNER"}'
r POST /talks '{"title":"T","format":"PRESENTATION","level":"BEGINNER","speakerId":987654321}'
r POST /talks '{not json'
r POST /talks '{"title":"T","format":"PRESENTATION","level":"BEGINNER","speakerId":"x"}'
r GET /talks/1
r GET /talks/987654321
r GET /talks/abc
r GET /talks
r GET '/talks?speakerId=1'
r GET '/talks?sort=bad'
r GET /speakers/1/talks
r GET /speakers/987654321/talks
# ratings
r PUT /talks/1/ratings/1 '{"score":4}'
r PUT /talks/1/ratings/2 '{"score":4}'
r PUT /talks/1/ratings/3 '{"score":4}'
r PUT /talks/1/ratings/4 '{"score":5}'
r PUT /talks/3/ratings/1 '{"score":2}'
r PUT /talks/3/ratings/1 '{"score":5}'
r PUT /talks/1/ratings/1 '{"score":0}'
r PUT /talks/1/ratings/1 '{"score":6}'
r PUT /talks/1/ratings/1 '{"score":3.5}'
r PUT /talks/1/ratings/1 '{"score":"3"}'
r PUT /talks/1/ratings/1 '{"score":null}'
r PUT /talks/1/ratings/1 '{}'
r PUT /talks/1/ratings/0 '{"score":3}'
r PUT /talks/1/ratings/abc '{"score":3}'
r PUT /talks/987654321/ratings/1 '{"score":3}'
r PUT /talks/1/ratings/1 '{"score":'
r GET /talks/1
r GET /talks/2
r GET '/talks?sort=rating'
# co-speakers
r PUT /talks/2/co-speakers/1
r PUT /talks/2/co-speakers/1
r PUT /talks/2/co-speakers/2
r PUT /talks/2/co-speakers/987654321
r PUT /talks/987654321/co-speakers/1
r DELETE /talks/2/co-speakers/1
r DELETE /talks/2/co-speakers/1
r GET /talks/2
# admin, docs, framework edges
r GET /admin/stats
r GET /admin/talks.csv
r GET /openapi.yaml
r GET /swagger-ui.html
r GET /nope
r DELETE /talks
r GET /actuator/health
r GET /health
```
