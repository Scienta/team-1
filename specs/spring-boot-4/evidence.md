# Spring Boot 4 upgrade: D12 evidence

Spec: `specs/spring-boot-4/spec.md` (D12, R12a). Collected 2026-11-06 on a developer machine.

## How

- **Before:** the Docker image built from `297f7d6` (origin/main with acceptance: Spring Boot 2.7.18,
  `eclipse-temurin:11-jre`, what production runs), against a fresh `postgres:16-alpine` database.
- **After:** the Docker image built from this branch (`docker build --platform linux/amd64`; Spring Boot 4.1.2,
  `eclipse-temurin:25-jre`), against a freshly recreated database in the same Postgres container.
- Both run with `PGHOST=<postgres container> PGSSLMODE=disable` on port 8080. The script below ran against each (122
  requests: every base endpoint, an unrated and a rated talk, co-speakers, acceptance (accept, reject, waitlist,
  withdraw, rejected transitions), every error case of R9-R12a incl. framework-written ones (type conversion,
  malformed JSON, missing body or parameter, 404/405/415, `@Valid`), `/health`, `/actuator/*`, `/admin/*`,
  `/openapi.yaml`, `/swagger-ui.html`, and a trailing-slash variant of every endpoint). It prints status,
  Content-Type, redirect and body per request; only `timestamp`, `submittedAt`, `ratedAtDisplay` and `generated`
  values are masked. Then `diff before.txt after.txt`. Workshops (merged later) aren't in the script; their own
  suites cover them.

## Result

**3 lines differ; everything else is byte for byte identical**, error bodies included (R12a): status codes,
Content-Types, redirects, field names and order, success bodies, framework-written `message` texts, `@Valid` `errors`,
acceptance, CSV (`6. nov. 2026`, D11), `openapi.yaml`, swagger-ui and all trailing-slash URLs.

| Request | Difference | Verdict |
|---|---|---|
| `GET /actuator/info`, `GET /actuator/info/` | `11.0.32.1 / 2.7.18 / 5.3.31` -> `25.0.5 / 4.1.2 / 7.0.10` | Expected (R3) |
| `POST /talks` with `{not json` | `column: 3` -> `column: 2` in the parse error's location | Not identical, below |

## Not identical (for the PO)

1. **The column in some Jackson parse errors.** Request bodies are read by Jackson 2 again (as on 2.7), but Jackson
   2.21 (managed by Boot 4.1.2) reports some error positions one column differently from 2.7's Jackson 2.13:
   - `POST /talks` body `{not json`: before `...
 at [Source: (org.springframework.util.StreamUtils$NonClosingInputStream); line: 1, column: 3]`,
     after `... line: 1, column: 2]`. Rest of the body identical.
   - `PUT /speakers/1` body `nope`: before `column: 5`, after `column: 1` (in `LegacyErrorBodiesTest`, not the script).

   Tried: Jackson 2.13.5 doesn't run with Spring 7 (`NoClassDefFoundError: com.fasterxml.jackson.databind.cfg.DatatypeFeature`);
   2.14.3 and 2.15.4 match all columns, but are years out of support and below what Boot 4 manages. 2.17-2.20 still
   differ on `{not json`. We kept Boot's 2.21.7 instead of pinning an unsupported parser on the request path. If the
   PO needs the columns too, pinning `jackson-core`/`jackson-databind` 2.15.4 works (tested, 22/22) at that cost.
2. **`@Valid` errors on more than one field were in a random order on 2.7.** Hibernate Validator returns a HashSet:
   12 identical `POST /speakers {}` against the 2.7 image gave email-first 4 times and name-first 8 times. 4.x
   now always lists them in `SpeakerForm`'s declaration order (name, email, bio), which is one of 2.7's answers, so
   a diff line here depends on 2.7's luck in that run (none in the final run).

## How it's made identical (`LegacyErrorBodies`)

- Request bodies go through a read-only Jackson 2 converter configured as Boot 2.7's (unknown fields ignored, JSON
  comments allowed, source in error locations), placed where 2.7 had its Jackson converter; responses are still
  written by Boot 4's Jackson 3. `com.fasterxml.jackson.core:jackson-databind` (Boot-managed 2.21.7) is a dependency
  again; the acceptance and workshop code from main already parses its text bodies with Jackson 2 and stays on it.
- `message` rebuilt as Spring 5 wrote it for `TypeMismatchException`, `HttpMessageNotReadableException`
  (Spring 5 appended `; nested exception is <cause>`), 415, 405, missing request parameter, unknown path.
- `@Valid` `errors`: Spring 5's property order per error, the form's declaration order between errors.
- Pinned by `LegacyErrorBodiesTest`: 22 requests, each sent 5 times, against bodies recorded from the 2.7 image
  (`src/test/resources/spring-boot-4/error-bodies-2.7.txt`); the two column cases compare with the column masked.

Differences found and fixed earlier (each pinned by a test): `submittedAt`/`submittedOn` formats (D4), alphabetical
property order (D8), trailing slashes on every path (D6, review #2), Boot 4 property names (JSON comments, error
`message`, encoding), error `timestamp` `+00:00`, `charset=UTF-8` on errors, `/actuator/health` probe groups.

## Gates (this branch, after merging main with acceptance and workshop registration)

- `./mvnw -B package` on Temurin 25.0.5: **153/153** green.
- Black-box suite (`black-box-tests`, `npm test`) against the 4.x image: **7 files, 110 tests** green.
- `examples/run.sh` (Hurl): **7 files, 170 requests** green; `examples/spring-boot-4.hurl` fails on the 2.7 image, as
  it should.

## After merge (D12 (4)): done, tick ~171

Squash-merged as `244682a` (#3); deploy run 42231778690: `tests` and `deploy` both green.

| Check on TEAM_URL | Result |
|---|---|
| `/health` | `{"status":"ok","version":"244682a5971f6f783f40f686d1a3fbf962a2242e"}` |
| `/actuator/info` | `{"versions":{"java":"25.0.5","springBoot":"4.1.2","springFramework":"7.0.10"}}` |
| `examples/run.sh` | 7 files, 170 requests, all green |
| Logs since the deploy (14:15Z) | 0 responses with a 5xx status |
| Harness at tick 171 | speakers, talks, co-speakers, acceptance all OK (workshop-registration check-2 FAULTY since tick 162, before this deploy: not caused by the upgrade) |
| Black-box suite against TEAM_URL | 108/110: the two failures are 30 s timeouts on `GET /talks` (25 s) and `/admin/stats` (35 s) with ~6,500 talks in production: N+1 queries that predate the upgrade; fixed separately on `fix/talk-list-performance` |

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
# trailing slashes on every endpoint (review #2)
r POST /talks/ '{"title":"Slash","format":"WORKSHOP","level":"BEGINNER","speakerId":2}'
r GET /talks/1/
r GET /talks/
r GET '/talks/?speakerId=1'
r GET '/talks/?sort=rating'
r PUT /talks/1/ratings/5/ '{"score":3}'
r PUT /talks/2/co-speakers/1/
r DELETE /talks/2/co-speakers/1/
r GET /speakers/1/talks/
r PUT /speakers/2/ '{"name":"Grace Hopper","email":"grace@example.com"}'
r GET /speakers/search/?q=grace
r GET /talks/987654321/
r GET /admin/stats/
r GET /admin/talks.csv/
r GET /health/
r GET /actuator/info/
r GET /actuator/health/
r GET /openapi.yaml/
# acceptance (merged from main at fb2e37f)
r PUT /talks/1/status '{"status":"ACCEPTED"}'
r PUT /talks/3/status '{"status":"ACCEPTED"}'
r PUT /talks/2/status '{"status":"WAITLISTED"}'
r PUT /talks/2/status '{"status":"REJECTED"}'
r PUT /talks/2/status '{"status":"ACCEPTED"}'
r PUT /talks/1/status '{"status":"ACCEPTED"}'
r PUT /talks/1/status/ '{"status":"ACCEPTED"}'
r PUT /talks/1/status 'nope'
r PUT /talks/1/status '{"status":"accepted"}'
r PUT /talks/1/status
r PUT /talks/987654321/status '{"status":"ACCEPTED"}'
r POST /talks/4/withdraw '{"speakerId":2}'
r POST /talks/4/withdraw '{"speakerId":"x"}'
r POST /talks/4/withdraw '{"speakerId":1}'
r POST /talks/4/withdraw '{"speakerId":1}'
r POST /talks/2/withdraw '{"speakerId":2}'
r GET /talks
r GET /admin/stats
r GET /admin/talks.csv
# more framework-written errors (R12a)
r POST /talks
r PUT /talks/1/ratings/1
r GET /speakers/search
r GET '/talks?speakerId=abc'
r POST /speakers '[1]'
r PUT /talks/1/ratings/1 '[]'
r POST /speakers '{"name":"Ok","email":"ok@example.com"} trailing'
```
