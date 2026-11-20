# Milestone review

After each milestone, start a fresh Claude chat and paste the prompt below, filling in the blanks.
A fresh chat keeps the review focused; everything it needs is in the repository and the spec doc.

## Review kickoff prompt

> I'm building an open-source Android podcast player with Claude Code, one milestone at a time.
> Spec doc: https://claude.ai/artifact/LW1ksBPjCSg44iiGy5AHBt
> Repository: <owner>/<repo>, branch `main` (attach it to this chat).
> Milestone just finished: M<n>.
>
> Please read CLAUDE.md, docs/SPEC.md, docs/LESSONS.md, docs/milestones/M<n>.md and docs/PROGRESS.md,
> then review the code against them. Run what you can (unit tests, the guardrail checks).
> I want back:
> 1. Findings ordered by severity, each with file and line, what is wrong, and why it matters.
> 2. Pass, fail or unverified for every item in the milestone's acceptance checklist.
> 3. Places where PROGRESS.md claims something the code doesn't do.
> 4. A fix-up prompt I can paste into Claude Code.
> 5. Any changes the spec or the next milestone's plan should get.

## What the reviewer checks

Rules
- Every CLAUDE.md rule, especially: no untrusted image decoding outside the sandbox, no WebView, one gated
  HTTP client (including ExoPlayer), address filter after DNS and redirects, parser limits, URL redaction,
  no closed dependencies, MPL headers, no GPL code.
- Playback: session as the single source of truth, queue projection, position persistence points,
  becoming-noisy handling, resumption, tests written for each rule.

Quality
- Tests exist for each LESSONS row touched by the milestone and actually exercise the behavior.
- Plain Kotlin modules have no `android.*` imports.
- Dependencies match the allowlist; versions pinned; verification metadata current.
- Errors become plain messages and logs, not crashes; debug invariants are used.
- Scope: nothing from later milestones was built early without being listed in PROGRESS.

Honesty
- PROGRESS.md matches the code; deviations are explained; manual checks are listed for the owner.
