---
name: feature
description: Run the team's feature workflow for a harness feature or change request, from feature request to pull request - check nobody has it, branch, spec (spec-writer agent), human gate, TDD implementation (implementer agent), independent review (reviewer agent), triage, PR. Use when someone says "/feature <slug>" or asks to start a new drip feature or change request.
---

# /feature <slug>

The process is described for people in `workflow/feature-process.md`. You (the main session) run
it, start each agent, and stop at the two human gates. Feature slugs are on the harness `/roadmap`.
Keep the human posted with one line per step.

## 0. Is it free?

```bash
git fetch -q origin
git ls-remote --heads origin "feat/<slug>"     # any output: a teammate has it. Stop and say who (git log).
```

Also check the harness feedback (`AGENTS.md`, Harness API) to see whether the feature is already
claimed, and `git log origin/main --oneline` for a merged implementation. If it's taken, stop.

## 1. Branch and mark it taken

```bash
git switch -c feat/<slug> origin/main
mkdir -p specs/<slug>
H=$(cat .fagdag/harness-url) TOKEN=$(cat .fagdag/harness-token)
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/specs/<slug>/spec.md" > specs/<slug>/request.md
git add specs/<slug>/request.md && git commit -m "chore(<slug>): start, feature request as released"
git push -u origin feat/<slug>
```

Pushing a `feat/` branch doesn't deploy (only `main` does) and is how teammates see the feature is
taken: it's part of this workflow and needs no extra confirmation. Never print the token.

## 2. Spec

Start the `spec-writer` agent: "Write the spec for `<slug>`; the feature request is at
`specs/<slug>/request.md`." Commit `specs/<slug>/spec.md` and push.

## 3. Human gate: the spec

Show the human the decision table and the open questions for the product owner, and **stop**. A
person asks the product owner; agents can't. When they answer, update the spec's decisions (mark
them "PO confirmed"), commit and push. If they say go without answers, note the questions as
unanswered in the spec and continue.

## 4. Implement (TDD)

Start the `implementer` agent: "Implement `specs/<slug>/spec.md` test-first on branch
`feat/<slug>`." Keep its agent id: it triages the review in step 6. Relay its report in a few lines.

## 5. Review

Start a **fresh** `reviewer` agent: "Review branch `feat/<slug>` against `specs/<slug>/spec.md`."
Give it nothing else: not the implementer's report and not your own opinion.

## 6. Triage

Send the reviewer's findings to the **same implementer** (SendMessage with its id) to agree or
reject each one, fix what it accepts, and write `specs/<slug>/review.md`. If it fixed a blocker,
run the reviewer once more on the new diff. At most two review rounds; leftover disagreements go
into the PR for a person to decide.

## 7. Pull request and hand-off

1. Check yourself: `./mvnw -q package` is green and `git status` is clean. Push.
2. Write the PR body from `workflow/templates/pr.md` into `specs/<slug>/pr.md` and commit it.
3. If `gh auth status` succeeds: `gh pr create --base main --head feat/<slug> --title "feat(<slug>): ..." --body-file specs/<slug>/pr.md`.
   Otherwise give the human the compare URL `https://github.com/Scienta/team-1/compare/main...feat/<slug>`
   and the body file to paste.
4. A PR is ready only when both checks on its head commit are green:
   - `tests` (`.github/workflows/tests.yml`): `./mvnw -B package`, the Java build and tests.
   - `black-box` (`.github/workflows/black-box.yml`): the Vitest suite.
   Check with `gh pr checks`. Pending or failing means the PR is not ready.
5. Tell the human: what's ready, the open questions, the rejected review findings, and the next steps
   (below). Don't say the PR is ready until both checks above are green. Don't say the feature is
   done or claimable: that comes after the merge and the checks.

## 8. After a person merges (deploys)

When asked: wait for the deploy (`curl $TEAM_URL/health` shows the merge SHA), run
`hurl --test --variable base_url=$TEAM_URL examples/<slug>.hurl`, read
`.github/fagdag/logs.sh --tail 100` for 5xx, and check the harness feedback for all claimed features.
Report the evidence. A **person** claims on the harness; then add the claim-log row to `REVIEW.md`
with the tick and that evidence (`.claude/rules/review-log.md`).
