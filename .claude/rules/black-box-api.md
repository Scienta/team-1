# Black-box API coverage

Whenever there is an API change or addition then review the black-box tests and add coverage or adjust coverage.

## Where the suite lives

- Suite: `black-box-tests/` (TypeScript + Vitest)
- Helpers: `black-box-tests/src/helpers/`
- Specs mirrored in tests: `black-box-tests/src/suites/*.test.ts`
- Status/edge decisions we assert: `black-box-tests/decisions.md`
- CI: `.github/workflows/black-box.yml` (`npm run test:ci`)
- Local: prefer `CFP_BASE_URL` (not bare `BASE_URL` — some environments set `BASE_URL=/`)

## When this applies

Any change that affects the HTTP surface, including:

- New or removed paths / methods
- Request or response JSON shape (fields, nullability, enums)
- Status codes or error behaviour
- Limits, validation, ordering, idempotency, concurrency rules clients can observe
- OpenAPI/`openapi.yaml` updates that reflect real behaviour

Does **not** replace Java `@SpringBootTest` tests or harness checks; it complements them.

## Required steps

1. Diff the API change against `black-box-tests/src/suites/` and helpers.
2. **Add** tests for new behaviour; **adjust** tests that assert old behaviour.
3. Record new status/edge choices in `black-box-tests/decisions.md` when the harness left them open.
4. Extend factories/helpers instead of duplicating fetch/assert logic.
5. Run the suite (`npm test` with app up, or `npm run test:ci` / `test:local` as appropriate) and fix failures.
6. Keep `examples/*.hurl` as light smoke if the change is a drip feature; Vitest is the deep suite.

## Done when

- New/changed API behaviour has intentional black-box coverage (or an explicit note why not).
- `decisions.md` matches what the tests assert.
- Black-box tests pass against a build that includes the API change.
