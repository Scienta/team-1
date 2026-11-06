# Upgrade to Spring Boot 4.x: our spec

Feature request: `specs/spring-boot-4/request.md` (harness slug `spring-boot-4`, V 3, released at tick 40).
Status: draft

A platform upgrade, not an endpoint. The rules are the invariants: what moves (R1-R3) and what must not
change for a client (R4-R14). Every rule cites where it is pinned today.

## Rules (from the feature request)

What moves:

- R1 The service runs on Spring Boot 4 ("the latest 4.x is fine"): **4.1.2** (D1), with Spring Framework
  7.0.10 as managed by that BOM.
- R2 It runs on a Java version supported for Boot 4: **Java 25** (D2), the same in the build image, the
  runtime image, the compiler `release`, and the CI `tests` job.
- R3 `GET /actuator/info` reports the **real running** versions: `versions.springBoot` (from
  `SpringBootVersion.getVersion()`), `versions.springFramework` (`SpringVersion.getVersion()`),
  `versions.java` (`System.getProperty("java.version")`), as `VersionInfoContributor` does today. No
  hard-coded or faked value anywhere (rules of conduct). After deploy it must read `4.1.2`, `7.0.10`, `25.x`.

What must not change ("everything in the Speakers, Talk submissions and Ratings specs keeps working exactly as
it does today, formats included"), plus the deploy contract:

- R4 `submittedAt` is exactly `yyyy-MM-dd'T'HH:mm:ss.SSS+00:00`, UTC, always milliseconds, offset `+00:00`,
  never `Z` (talks spec; `TalksTest.aTalkReadsBackAsSubmittedWithItsStatusAndTimestamps`). Today it is a
  `java.util.Date` serialised by Jackson 2's `StdDateFormat`.
- R5 `submittedOn` is exactly `yyyy-MM-dd`, the UTC date of `submittedAt` (talks spec). Today it is a
  `java.sql.Date` serialised by Jackson 2 as its `toString()`, not as epoch millis.
- R6 `ratedAtDisplay` is exactly `M/d/yy, h:mm AM` with an ordinary space, UTC (ratings spec). Built by
  `Utils.usShort` with an explicit `SimpleDateFormat` pattern, so the JDK 20+ U+202F change can't reach it
  (`UtilsTest.usShortIsPlainAsciiOnEveryJdk`).
- R7 Nulls are written, not dropped: `averageRating: null` on an unrated talk, `bio: null` on a speaker
  without one. Number types unchanged: `averageRating` a JSON number rounded to one decimal, ids integers.
- R8 Field names unchanged in every response (`TalkDto` public fields, the `LinkedHashMap` bodies of
  speakers and ratings, `/health`).
- R9 Status codes unchanged, including framework-produced ones: `400` for a non-numeric path id
  (`PUT /talks/{id}/ratings/abc`, `RatingsTest`), `400` for malformed JSON, `400` for `@Valid` failures on
  `POST /speakers`, `400` for a non-integer `score` (`score instanceof Integer` in `ConferenceService.rate`),
  `404`/`409` from `ResponseStatusException` as today.
- R10 Unknown request fields are ignored, not rejected: `POST /talks` with `durationMinutes: 999` is `201`
  with the format's duration (`TalksTest.theFormatDecidesTheDurationNotTheClient`).
- R11 The legacy trailing-slash URLs keep working: `POST /speakers/` and `GET /speakers/{id}/` behave like
  the URLs without a slash (speakers spec; `SpeakersTest.theLegacyTrailingSlashUrlsWork`).
- R12 Error bodies keep Boot's shape `{timestamp, status, error, message, path}` with `message` filled in
  (`server.error.include-message=always`), not RFC 9457 problem details.
- R13 The deploy contract holds: port 8080, `GET /health` exactly `{"status":"ok","version":"<GIT_SHA>"}` /
  503 `{"status":"db-unavailable",...}` within 5 s (`HealthTest`), `PG*` variables, `PGSSLMODE=require` in
  the image, `--build-arg GIT_SHA`, `linux/amd64`, pool of 5.
- R14 Our tooling keeps working: the request logging filter logs one line per request plus the error-dispatch
  line (`RequestLoggingTest`), `/openapi.yaml` and `/swagger-ui.html` are served (`OpenApiTest`), and
  `/admin/stats`, `/admin/talks.csv` answer as before.

## API

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/actuator/info` | - | 200 `{"versions":{"java":"25.x","springBoot":"4.1.2","springFramework":"7.0.10"}}` |

No new or changed endpoints. New or changed fields in existing responses: none (only the values in
`/actuator/info`).

## What moves in this codebase (implementation checklist)

| Where | Today | Target |
|---|---|---|
| `pom.xml` `spring-boot.version` (BOM import + `spring-boot-maven-plugin`) | 2.7.18 | 4.1.2 |
| `pom.xml` `java.version`, `maven.compiler.release`, compiler `<release>` | 11 | 25 |
| `maven-compiler-plugin` | 3.10.1 | 3.15.0+ and **`<parameters>true</parameters>`** (D5) |
| `spring-boot-starter-web` | as is | `spring-boot-starter-webmvc` (the old name is deprecated in Boot 4) |
| `springdoc-openapi-ui` 1.7.0 (Boot 2 only) | 1.7.0 | `springdoc-openapi-starter-webmvc-ui` 3.2.0 (built against Boot 4.1.2) |
| Testcontainers | `org.testcontainers:postgresql` 1.21.4, own version property | `org.testcontainers:testcontainers-postgresql`, version from the Boot BOM (2.0.5); drop `testcontainers.version`. Class is `org.testcontainers.postgresql.PostgreSQLContainer` (no generic) in `TestDatabase`, `HealthTest`, `OpenApiTest` |
| `Dockerfile` | `maven:3.9-eclipse-temurin-11`, `eclipse-temurin:11-jre` | `maven:3.9-eclipse-temurin-25`, `eclipse-temurin:25-jre` (both exist on Docker Hub) |
| `.github/workflows/deploy.yml` `tests` job | `java-version: "11"` | `"25"` |
| `javax.servlet.*` in `RequestLoggingFilter` | javax | `jakarta.servlet.*` |
| `javax.validation.*` in `SpeakerController`, `SpeakerForm` | javax | `jakarta.validation.*` |
| Jackson in main code | Jackson 2 via Boot | Jackson 3 (`tools.jackson.*`) is Boot 4's default; main code has no direct Jackson imports |
| Jackson in tests (`Api`, `RatingsTest`, `SpeakersTest`, `TalksTest`) | `com.fasterxml.jackson.databind` | `tools.jackson.databind` (`asText()` is `asString()` in 3); Boot 4 no longer puts Jackson 2 databind on the classpath |
| `spring.jackson.parser.allow-comments=true` | Boot 2 property | its Boot 4 / Jackson 3 equivalent under `spring.jackson.json.read.*` (D7); confirm the name in the Boot 4.1 property reference or with `spring-boot-properties-migrator` |
| springdoc properties | `springdoc.swagger-ui.*` | same keys in springdoc 3; re-check with `OpenApiTest` |

## Decisions (where the request is silent)

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | Which Boot 4.x | 4.1.2 | Latest GA on Maven Central today (4.2.0 is still RC2; milestones/RCs ignored); matches the request's example |
| D2 | Which Java | 25 (Temurin) | Latest LTS, supported by Boot 4 (17-25); `maven:3.9-eclipse-temurin-25` and `eclipse-temurin:25-jre` exist; matches the request's example `25.0.5`. 21 is the fallback if something blocks on 25 |
| D3 | Jackson 2 or 3 | Jackson 3, Boot's default | Supported path; pin the formats with tests instead of staying on the deprecated Jackson 2 bridge |
| D4 | `submittedAt`/`submittedOn` if Jackson 3 renders them differently (`Z`, no millis, epoch number, `java.sql.Date` as a timestamp) | Fix in our code, not by global mapper tweaks we can't see: format explicitly to the exact strings of R4/R5 (e.g. `@JsonFormat(pattern=..., timezone="UTC")` or a pre-formatted String) | The spec pins strings, not types; explicit is robust to the next library bump |
| D5 | `@PathVariable Long id` / `@RequestParam String sort` without explicit names | Compile with `-parameters` (`<parameters>true</parameters>`) | Spring 6.1+ dropped bytecode-based name discovery; without `spring-boot-starter-parent` (we import the BOM) nothing sets `-parameters`, and every such endpoint would fail at runtime |
| D6 | Trailing slashes (R11) | Keep them working explicitly: either map both paths in `SpeakerController` or register Spring's `UrlHandlerFilter` for `/speakers/**` (it must not redirect; `POST /speakers/` must stay `201`) | Spring 6 turned trailing-slash matching off; as shipped this would become `404` |
| D7 | JSON comments in request bodies | Keep allowed | Today's behaviour ("the mobile client depends on some of these"); no reason to change |
| D8 | Property order in `Talk` JSON | Keep declaration order (`id, title, format, ...`); disable `MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` if Jackson 3 sorts | Jackson 3 sorts properties alphabetically by default; order isn't semantic, but "exactly as it does today" is cheap to keep |
| D9 | Stricter Jackson 3 defaults (trailing tokens after the JSON value, null into primitives) | Accept as long as R9/R10 hold; our forms use boxed types | Only affects garbage input; would be `400` either way |
| D10 | Unknown fields (R10) | Must stay ignored; if Boot 4's mapper fails on them, set `spring.jackson.deserialization.fail-on-unknown-properties=false` | `TalksTest` pins it; the harness posted `durationMinutes` before |
| D11 | `/admin/talks.csv` date (`Utils.norwegianDate`, CLDR `MEDIUM` for `nb_NO`) | Compare before/after; if JDK 25's CLDR renders it differently from `4. nov. 2026`, switch to an explicit pattern | Not in a base spec, but "nothing else changes"; locale data does change between JDK 11 and 25 |
| D12 | Proof that nothing else changed | (1) `./mvnw package` green on Java 25 with the full existing suite, unchanged except for imports/class names above; (2) `examples/run.sh` green locally; (3) before/after diff: run the same request script (every base endpoint, an unrated and a rated talk, each error case of R9/R10/R11, `/health`, `/actuator/info`, `/admin/*`) against the 2.7 jar and the 4.x jar, normalise ids/timestamps, diff bodies and status codes; only `/actuator/info` values may differ; (4) after deploy, `/actuator/info` on `TEAM_URL` shows 4.1.2 / 7.0.10 / 25.x and the harness feedback for base features stays green | Tests pin rules, the diff catches what tests don't pin |
| D13 | Test-only additions | Add tests for what the suite doesn't pin yet: `submittedOn` exact pattern, malformed JSON `400`, `averageRating`/`bio` written as `null`, `/actuator/info` versions equal the runtime's (`SpringBootVersion`, `SpringVersion`, `java.version`) and start with `4.` / `7.` | R3-R10 must be checked, not assumed |

## Concurrency

Nothing new. The existing invariants (max 3 talks per speaker, one rating per reviewer per talk, co-speaker
limit) keep their row locks; the pool stays at 5. The existing concurrency tests must pass unchanged.

## Changes to existing behaviour

None for clients except the version values in `/actuator/info`. Existing tests change only mechanically:
Jackson 3 imports and `asString()`, Testcontainers 2 class/package names. No assertion may be weakened.

## Assumptions

- The harness's base-feature checks compare the formats exactly as the base specs state them (R4-R7).
- Boot 4 still disables `FAIL_ON_UNKNOWN_PROPERTIES` and `WRITE_DATES_AS_TIMESTAMPS` by default with
  Jackson 3; D4/D10 cover it if not.
- The Scaleway Postgres and the 42.7.x driver work unchanged with Java 25 and `sslmode=require`.
- `VersionInfoContributor`'s imports (`org.springframework.boot.actuate.info.*`, `SpringBootVersion`,
  `SpringVersion`) still exist in Boot 4's modular actuator; only imports move if not.
- Scale-to-zero cold start on Java 25 / Boot 4 stays within the platform's limits (startup is comparable).

## Open questions for the product owner

None. The request leaves no business rule open; all choices above are technical.
