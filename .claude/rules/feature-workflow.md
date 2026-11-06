# New features go through /feature

- A new harness drip feature or change request goes through the `/feature <slug>` skill
  (`.claude/skills/feature/SKILL.md`, process in `workflow/feature-process.md`). Don't start one ad hoc.
- Before starting any feature work, check that nobody has it:
  `git fetch -q origin && git ls-remote --heads origin "feat/<slug>"`. If the branch exists, a
  teammate owns it: don't build it, offer to review or test it instead.
- One feature per `feat/<slug>` branch. Pushing a `feat/` branch is fine (it doesn't deploy);
  pushing or merging to `main` deploys and needs a person's go-ahead.
- Our specs live in `specs/<slug>/` (`request.md` as released, `spec.md` ours, `review.md`, `pr.md`).
- Agents never claim on the harness and never say a feature is done without evidence a person can check.
