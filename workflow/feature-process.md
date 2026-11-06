# Feature process: from feature request to claim

How we take a harness drip feature or change request to a claim. In Claude Code, type
`/feature <slug>` and it runs this for you (`.claude/skills/feature/SKILL.md`), stopping where a
person is needed.

```
 free? ─► branch ─► spec ─► [PERSON: PO questions] ─► TDD implement ─► review ─► triage ─► PR
                  spec-writer                          implementer      reviewer  implementer
                                                                                       │
 claim ◄─ [PERSON] ◄─ checks on TEAM_URL ◄─ deploy ◄─ [PERSON: merge to main] ◄────────┘
```

| Step | Who | Output |
|---|---|---|
| 0. Is it free? | main session | no `feat/<slug>` branch on origin, not claimed, not merged |
| 1. Branch | main session | `feat/<slug>` pushed (this marks it taken), `specs/<slug>/request.md` |
| 2. Spec | `spec-writer` agent | `specs/<slug>/spec.md`: rules R1.., decision table, assumptions, PO questions |
| 3. Spec gate | **person** | asks the product owner, or says go; answers recorded in the spec |
| 4. Implement | `implementer` agent | failing tests first, each citing a rule id; code; `examples/<slug>.hurl`; green `./mvnw package` |
| 5. Review | `reviewer` agent, fresh context, read-only | findings with severity, rule id, `file:line`, failing scenario |
| 6. Triage | the same `implementer` | agree and fix (with a test), or reject with a reason: `specs/<slug>/review.md` |
| 7. PR | main session | PR from `workflow/templates/pr.md`; the person is told what's ready |
| 8. Merge | **person** | merge to `main` deploys; the deploy waits for the tests to pass |
| 9. Verify | main session | `/health` SHA, Hurl green on TEAM_URL, no 5xx in logs, all claimed checks OK |
| 10. Claim | **person** | claims on the harness; claim-log row in `REVIEW.md` |

## Why it's shaped like this

- **One feature, one branch, pushed at once.** Two of us built co-speakers in parallel before we had
  this; a pushed `feat/<slug>` branch is the "taken" sign, and it doesn't deploy.
- **A person between spec and code.** Our status-code guesses decide whether harness checks pass, and
  only people can ask the product owner.
- **Tests cite rule ids.** The reviewer can see at a glance which rule has no test.
- **The reviewer starts fresh and can't edit.** It judges the spec and the diff, not the
  implementer's reasoning, so it isn't anchored.
- **The implementer triages.** It knows the code best, but has to write down why it rejects a finding,
  and leftovers go to a person in the PR.
- **Tests gate the deploy.** The base features earn every tick; one bad merge makes them bleed.
- **Timeboxes.** A tick is about 1.2 minutes, so a V 2 feature left unclaimed for an hour costs about
  100 points: spec about 10 minutes, at most two review rounds.
