---
description: Run several milestone sessions in order, unattended, e.g. /batch M1-S2 M1-S3 M1-S4
argument-hint: <session ids in order>
---

You are orchestrating these sessions, in this order: $ARGUMENTS

Do not write product code yourself. For each session, one at a time:

1. Get an up-to-date main branch: `git switch main`, then `git pull`.
2. Start a subagent with fresh context and give it this task, with the session id filled in:

   "Read CLAUDE.md, docs/PROGRESS.md and session <id> in docs/milestones/. Write your plan as the first part
   of this session's entry in docs/PROGRESS.md, then carry it out without waiting for approval. Write the tests
   for each rule before its code. Run ./gradlew check until it passes. Never weaken a rule in CLAUDE.md, never add
   a dependency outside the allowlist in docs/SPEC.md, and never edit CLAUDE.md, docs/SPEC.md or .claude/.
   If you hit any of those limits, or need a decision from the owner, stop, record it under 'Questions for owner'
   in docs/PROGRESS.md, commit, and report back. Otherwise finish by updating docs/PROGRESS.md, committing on a
   branch named after the session (for example m1-s2-network-gate), pushing it with `git push -u origin <branch>`,
   and opening a pull request. Report back: the pull request number, what was built, tests added, deviations,
   and manual checks for the owner."

3. When the subagent reports, record the pull request's head commit
   (`gh pr view <number> --json headRefOid -q .headRefOid`), then verify before merging:
   - the pull request exists and `gh pr checks <number> --watch` ends green;
   - the diff adds no dependency outside the allowlist in docs/SPEC.md and does not touch CLAUDE.md,
     docs/SPEC.md or .claude/;
   - docs/PROGRESS.md has no new "Questions for owner" from this session.
4. If everything passes, merge with `gh pr merge <number> --squash --delete-branch --match-head-commit <sha>`,
   using the head commit recorded in step 3, and go to the next session. If the merge is refused because the
   head changed, stop the batch and tell me.

Stop the whole batch, without merging, and tell me why, if:
- a check fails and one fix attempt by a new subagent does not resolve it;
- a subagent recorded a question for me or wanted to break a rule above;
- the session's work can only be finished with a real phone, car or Desktop Head Unit.

At the end, give me one short block per session: pull request link, what was built, tests added, deviations,
and the manual checks I should do on the phone.
