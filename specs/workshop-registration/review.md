# Review: workshop-registration

Independent reviewer on `feat/workshop-registration` at `5abb497` (verdict: ready, no blockers). Triaged against
`spec.md` and the code.

| # | Finding | Severity | Verdict | What we did, or why not |
|---|---|---|---|---|
| 1 | Postgres `trim()` strips only spaces, so `"\tada@example.com"` after `"ada@example.com"` got a second registration (D12/R6) | nit | Agree | D9 says "surrounding whitespace ignored", and a tab is whitespace, so this was a bug against our own spec, not a wording issue. The check and the unique index now both use `lower(btrim(email, E' \t\n\r\f\013'))`, which strips the same characters as Java's `trim()` and `Utils.isEmpty`. The SQL is in `RegistrationDao.normalised`, and the index got a new name (`ux_workshop_registration_email_ws`) and replaces `ux_workshop_registration_email`. That old index never reached `main` or production, so changing it here is safe. A local database that ran the earlier commit keeps the old index too, which is harmless because it's only looser. New tests: `d9d12_whitespaceOtherThanSpacesAroundTheEmailIsTheSamePerson` (API, failed with 201 before the fix) and `d12_theUniqueIndexAlsoIgnoresCaseAndSurroundingWhitespace` (inserts straight into the database; fails with the old `lower(trim(email))` index). Vitest R6 case and `examples/workshop-registration.hurl` also send a tab-padded duplicate. The spec's D12 wording ("`lower(trim(email))`") is left to the spec owner. |
| 2 | Hurl "another case" comment, but no request changed the letter case | nit | Agree | Added `ada_other_case` (captured with `replace`: `ada-…@EXAMPLE.COM` vs the registered `ADA-…@Example.com`) and a 409 request using it. Asserts on the capture request prove the filter changes the case, and the app log shows the lowercased email was sent and refused with 409. |
| 3 | Spec header stale: "ready to implement once acceptance is on main" / "not on `main` yet" | nit | Agree | Updated only the Status and Depends lines. Acceptance is on `main` since `fb2e37f`, and this branch includes it via `dccb8d2`. No rules or decisions changed. |

Verification after the fixes: `./mvnw -q package` passes with 116 tests (31 in `WorkshopRegistrationTest`), 0 failures,
0 skipped. `examples/workshop-registration.hurl` and the Vitest suite (110 tests) are green against a local jar. Not
yet run against the deployed service or the harness.
