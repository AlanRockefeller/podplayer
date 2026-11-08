---
description: Plan and run one milestone session, e.g. /session M1-S3
argument-hint: <session id, e.g. M1-S3>
---

Read CLAUDE.md, docs/PROGRESS.md, and session $ARGUMENTS in the matching file under docs/milestones/.

Plan that session only: list the files you will create or change, the tests you will write first,
the SPEC and LESSONS sections you are applying, and anything in the spec that seems unclear.
Wait for my approval before writing code.

When the work is done: run ./gradlew check until it passes, update docs/PROGRESS.md
(done, tests added, deviations and why, manual checks for me, questions), commit on a branch named
after the session, and open a pull request.
