# <Feature name>: our spec

Feature request: `specs/<slug>/request.md` (harness slug `<slug>`, V <n>, released at tick <n>).
Status: draft | PO questions asked | PO confirmed

## Rules (from the feature request)

- R1 ...
- R2 ...

## API

| Method | Path | Body | Success |
|---|---|---|---|
| | | | |

New or changed fields in existing responses: ...

## Decisions (where the request is silent)

| # | Case | Our answer | Why |
|---|---|---|---|
| D1 | unknown id in the path | 404 | same as `GET /talks/{id}` |
| D2 | | | |

## Concurrency

Which limits or invariants must hold under parallel requests, and how (row lock, conditional write).

## Changes to existing behaviour

Base or claimed features whose responses change, and which existing tests must change. "None" if none.

## Assumptions

- ...

## Open questions for the product owner

Ranked by how likely a wrong guess fails a harness check. Answers go here, with who asked and when.

1. ...
