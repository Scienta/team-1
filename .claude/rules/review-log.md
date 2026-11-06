# Keep team-1-REVIEW.md current

`team-1-REVIEW.md` is the team's running log and what we present at 17:00. Update it as you go, in the
same commit as the work when there is one, not at the end of the day.

## When to write what

- **After a deploy, or a change that affects how the team works** (a new test pattern, gate,
  script, rule or agent): add it to the running log in section 1, and update the workflow steps
  there if one changed.
- **When something reviewed agent output** (tests, a person's review, a review agent, Hurl examples,
  the deploy workflow's gates, harness transcripts): add it to section 2, saying what it checked
  against.
- **When a person claims or skips a feature:** add a row to the claim log (section 3) with the tick
  (from `GET $H/api/teams/<team>/feedback`) and the evidence held before claiming. Agents can't
  claim and must not write a row for a claim no person has made.
- **When the harness reports a FAULTY check we hadn't caught:** add it to section 4: the check, what
  it sent, what we answered, and why our own tests missed it.
- **When we build something reusable** (a script, filter, template, rule, agent): add it to
  section 5 with its path.
- **Section 6** is the team's own judgement: leave it to a person.

## How

- Only write what happened and can be checked: commit SHAs, ticks, check names, file paths. No
  guesses and no "done" without evidence.
- Keep the template's headings. Replace a heading's prompt comment once there is real text under it.
- Never write the harness token, log tokens or other secrets into these files.
