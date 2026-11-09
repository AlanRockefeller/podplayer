---
description: Plan and run one milestone session, e.g. /session M1-S3
argument-hint: <session id, e.g. M1-S3>
---

Read CLAUDE.md, docs/PROGRESS.md, and session $ARGUMENTS in the matching file under docs/milestones/.

Plan that session only: list the files you will create or change, the tests you will write first,
the SPEC and LESSONS sections you are applying, and anything in the spec that seems unclear.
Wait for my approval before writing code.

When the work is done:
1. Run ./gradlew check until it passes.
2. Update docs/PROGRESS.md (done, tests added, deviations and why, manual checks for me, questions),
   commit on a branch named after the session, push it with `git push -u origin <branch>`,
   and open a pull request.
3. Run `gh pr checks <number> --watch` until CI finishes. If it fails, fix the cause, push again, and repeat.
4. If `adb devices` lists my phone, run ./gradlew :app:installDebug. Then tell me in two or three lines exactly
   what to look at on the phone, taken from the session's "Done when" line, plus any manual checks.
5. Ask me whether to merge. Only after I say yes: `gh pr merge <number> --squash --delete-branch`,
   then `git switch main` and `git pull`.
