## Upgrade to Spring Boot 4.x (`spring-boot-4`, V 3)

Feature request: `specs/spring-boot-4/request.md` · Our spec: `specs/spring-boot-4/spec.md` · Evidence: `specs/spring-boot-4/evidence.md` · Review triage: `specs/spring-boot-4/review.md`

First feature run through `/feature` end to end: spec-writer → person approved the spec (no PO questions) → implementer (test-first) → fresh read-only reviewer → implementer triage.

### What changed

- **Spring Boot 2.7.x → 4.1.2**, Spring Framework 7.0.10, **Java 11 → 25** (Temurin), Jackson 3, Testcontainers 2, springdoc 3.
- `Dockerfile`: `maven:3.9-eclipse-temurin-25` → `eclipse-temurin:25-jre`.
- **`.java-version` (25)**: both workflows (`deploy.yml` tests job, `black-box.yml`) read it, so the Java version can't drift again.
- `javax.*` → `jakarta.*` (`RequestLoggingFilter`, `SpeakerController`, `SpeakerForm`); `-parameters` compiler flag (D5).
- `TalkDto`: `submittedAt`/`submittedOn` formatted explicitly (D4).
- New `WebConfig`: trailing-slash URLs keep working on **every** path, as on 2.7 (D6, review #2); error `timestamp` `+00:00` and `charset=UTF-8` on error responses as before.
- `application.properties`: Boot 4 property names; alphabetical JSON property sorting off (D8); JSON comments still allowed (D7).

### Proof that clients see no change

| Evidence | Result |
|---|---|
| `./mvnw package` on Temurin 25.0.5 (implementer, and again by the reviewer) | **68/68** green; existing tests changed only mechanically (imports, Jackson 3 method names, Testcontainers 2 class) |
| Before/after: same 96-request script against the 2.7 image (Java 11, as production) and the 4.x image, fresh DB each | Status codes, content types, every success body, field order, CSV, `openapi.yaml`, swagger-ui, all trailing-slash URLs **identical**; only `/actuator/info` versions differ, plus framework wording of `message` in 13 error bodies for garbage input (status and shape unchanged, D9) |
| `examples/run.sh` (Hurl) against the 4.x image | 5 files, 40 requests green; `examples/spring-boot-4.hurl` fails on 2.7 (catches a missing upgrade) |
| Black-box suite (`black-box-tests/`, Vitest) against the 4.x jar on Java 25 | **35/35** green |
| `/actuator/info` locally | `java 25.0.5`, `springBoot 4.1.2`, `springFramework 7.0.10`, read from the runtime (pinned by `UpgradeInvariantsTest`, not hard-coded) |

Rule → test mapping: `specs/spring-boot-4/evidence.md` and the spec (R1–R14, D1–D13).

### Review

| # | Finding | Severity | Verdict | What we did |
|---|---|---|---|---|
| 1 | No Hurl example; `run.sh` never run on 4.x | should-fix | agree | Added `examples/spring-boot-4.hurl`; installed Hurl; `run.sh` green on 4.x |
| 2 | Trailing slashes restored only for `/speakers/**`; 2.7 matched them everywhere | nit | agree | Filter on `/**`; new test failed before; 18 slash requests added to before/after, all identical |
| 3 | After-deploy check open | nit | agree | Listed under "After merge" in `evidence.md` |

### Open questions for the product owner

None needed by the request. FYI for the team: framework-written error `message` texts on malformed input changed wording (status codes and body shape unchanged).

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
