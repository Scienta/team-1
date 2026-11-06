## Upgrade to Spring Boot 4.x (`spring-boot-4`, V 3)

Feature request: `specs/spring-boot-4/request.md` · Our spec: `specs/spring-boot-4/spec.md` · Evidence: `specs/spring-boot-4/evidence.md` · Review triage: `specs/spring-boot-4/review.md`

First feature run through `/feature` end to end: spec-writer → person approved the spec (no PO questions) → implementer (test-first) → fresh read-only reviewer → implementer triage.

### What changed

- **Spring Boot 2.7.x → 4.1.2**, Spring Framework 7.0.10, **Java 11 → 25** (Temurin), Jackson 3, Testcontainers 2, springdoc 3.
- `Dockerfile`: `maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre`.
- **`.java-version` (25)**: both workflows (`deploy.yml` tests job, `black-box.yml`) read it, so the Java version can't drift again.
- `javax.*` → `jakarta.*` (`RequestLoggingFilter`, `SpeakerController`, `SpeakerForm`); `-parameters` compiler flag (D5).
- `TalkDto`: `submittedAt`/`submittedOn` formatted explicitly (D4).
- New `WebConfig`: trailing-slash URLs keep working on **every** path, as on 2.7 (D6, review #2); `charset=UTF-8` on error responses as before.
- New `LegacyErrorBodies`: error bodies byte for byte as on 2.7 (R12a): Jackson 2 reads request bodies (`jackson-databind` 2.x is a dependency again), Spring 5 `message` texts, `@Valid` `errors` order, `timestamp` `+00:00`.
- `application.properties`: Boot 4 property names; alphabetical JSON property sorting off (D8); JSON comments still allowed (D7).

### Proof that clients see no change

| Evidence | Result |
|---|---|
| `./mvnw package` on Temurin 25.0.5, after merging main (acceptance, its 409 fix, workshop registration) | **153/153** green; existing and merged tests changed only mechanically (imports, Jackson 3 method names, Testcontainers 2 class, `getStatusCode`) |
| Before/after: same 122-request script against the 2.7 image (from main `297f7d6`, Java 11, as production) and the 4.x image, fresh DB each; covers base, co-speakers, acceptance, every framework-written error and trailing slashes | **3 lines differ**: `/actuator/info` versions (2, expected) and one Jackson parse-error column (see Open questions). Everything else byte for byte identical, error bodies included (R12a) |
| `LegacyErrorBodiesTest`: 22 error responses x5 against bodies recorded from the 2.7 image | green (2 compared with the column masked) |
| `examples/run.sh` (Hurl) against the 4.x image | 7 files, 170 requests green; `examples/spring-boot-4.hurl` fails on 2.7 (catches a missing upgrade) |
| Black-box suite (`black-box-tests/`, Vitest) against the 4.x image | **110/110** green (7 files, incl. acceptance and workshop registration) |
| `/actuator/info` locally | `java 25.0.5`, `springBoot 4.1.2`, `springFramework 7.0.10`, read from the runtime (pinned by `UpgradeInvariantsTest`, not hard-coded) |

How the error bodies are made identical: `LegacyErrorBodies` (Jackson 2 reads request bodies as on 2.7, responses stay
Jackson 3; Spring 5 `message` texts rebuilt; `@Valid` `errors` in Spring 5's order). Details: `evidence.md`.

Rule → test mapping: `specs/spring-boot-4/evidence.md` and the spec (R1–R14, R12a, D1–D13, D9a).

### Review

| # | Finding | Severity | Verdict | What we did |
|---|---|---|---|---|
| 1 | No Hurl example; `run.sh` never run on 4.x | should-fix | agree | Added `examples/spring-boot-4.hurl`; installed Hurl; `run.sh` green on 4.x |
| 2 | Trailing slashes restored only for `/speakers/**`; 2.7 matched them everywhere | nit | agree | Filter on `/**`; new test failed before; 18 slash requests added to before/after, all identical |
| 3 | After-deploy check open | nit | agree | Listed under "After merge" in `evidence.md` |

### Open questions for the product owner

Error bodies now match 2.7 byte for byte (R12a), with two exceptions listed in `evidence.md` under "Not identical":

1. **Column numbers in some JSON parse errors** (e.g. `{not json`: `column: 3` on 2.7, `column: 2` now). Jackson
   2.21, which Boot 4 manages, counts differently from 2.7's 2.13. Pinning Jackson 2.15.4 matches it (tested), but
   that version is out of support and older than what Boot 4 manages, so we didn't. PO: is the column acceptable,
   or do we pin 2.15.4?
2. **`@Valid` errors on several fields**: 2.7 listed them in a random order per request; we now always use the
   form's field order (one of 2.7's answers). Nothing to decide unless the PO wants something else.

### Merge notes

- **Merging deploys** (tests gate first). The deploy builds on Java 25.
- Maria's `feat/acceptance` touches the same areas (imports, test helpers): whichever merges second gets small mechanical conflicts; re-run `./mvnw package` on Java 25 after resolving.
- Locally you now need **JDK 25** to build (`winget install EclipseAdoptium.Temurin.25.JDK`).

### Before claiming (after merge, a person checks)

- [ ] deploy green, `/health` shows the merge SHA
- [ ] `/actuator/info` on TEAM_URL shows 4.1.2 / 7.0.10 / 25.x
- [ ] `examples/run.sh https://fagsem-h2026-team-1.scienta.cloud` green
- [ ] no 5xx in `.github/fagdag/logs.sh`
- [ ] harness: speakers, talks, ratings, co-speakers still all OK
- [ ] a person claims `spring-boot-4`; claim-log row added to `REVIEW.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
