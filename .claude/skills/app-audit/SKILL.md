---
name: app-audit
description: Run a deep, multi-dimension code audit of an app (performance and repeated work, sync/reconciliation data safety, storage crypto and privacy, background-worker battery and network cost, process death and state restoration, test coverage gaps) using parallel read-only reviewers, then optionally fix every finding in parallel worktrees and re-audit the result. Use this whenever the user asks to audit, review, or check the app for performance, slowness, repeated work, battery drain, data loss, security, robustness, or "what else should we check", or asks to redo/repeat the audits, even if they only name one dimension.
---

# App audit

The audit that found and fixed 150+ issues in Lenswave in one day worked because of four
habits, and this skill exists to keep them:

1. **Reviewers read whole files and cite lines.** A reviewer that skims produces plausible
   guesses; one that reads every file in its area and quotes `file:line` produces defects you
   can act on. Give each reviewer a bounded area it can read completely.
2. **One reviewer per dimension, in parallel.** Performance, data safety, security, battery and
   restoration need different mental models. A single generalist review misses most of them.
3. **Fix, then audit the fixed code again.** The first fix round introduced real regressions
   (a debounce defeated by a forced flush, a "streaming" decrypt that was not streaming, a
   marker written over a broken database). The second pass is where those get caught.
4. **Verify the headline claims yourself** before publishing. Reviewers are usually right, but
   the report carries your name.

## Before you start

- Establish the exact commit under review and put a **detached, read-only worktree** at it.
  Reviewers read from there so concurrent edits (yours or another session's) cannot shift line
  numbers under them. Use a short path such as `<repos>/lw-audit` on Windows: long paths break
  `git worktree remove`.
- Read the repo's `CLAUDE.md` / `CONTRIBUTING.md` for the check chain, the coding rules and the
  layout; reviewers need the file map, fixers need the rules.
- Look for a project-specific companion skill or reference (for example a `<app>-audit` skill in
  the repo's `.claude/skills/`) that carries the area file map, the check chain, where state
  lives and what earlier audits already fixed; read it before writing reviewer prompts.

## Run the audit

Spawn one **read-only** reviewer (an `Explore`-type subagent) per dimension, all in the same
turn so they finish together. The dimension checklists live in `references/dimensions.md`;
read that file and hand each reviewer its section verbatim plus the repo-specific file list.

Every reviewer prompt needs these parts, or the report comes back vague:

- The worktree path, the commit, "do not modify anything", the source root.
- The exact files in its area (a list, not "the gallery"), and an instruction to read each fully.
- What a previous audit already fixed, so it looks for what is *still there or newly introduced*.
- The dimension's checklist of concrete questions and scenarios to trace end to end.
- The output contract: per finding `file:line`, what happens, cost scaling or the failing
  sequence, a concrete fix, a severity (critical/high/medium/low); then an explicit list of
  what was checked and is fine. "No speculation beyond what the code shows."

The six dimensions, with the reviewer that owns each:

| Dimension | Looks for |
|---|---|
| Performance (split into 3-4 area reviewers: data layer, UI list/grid, background pipeline, detail viewer) | Work done more than once, O(n²), main-thread IO/decode, locks across slow work, allocation storms, cold-start critical path |
| Sync and reconciliation | Data loss or resurrection under races, process kills between multi-file writes, account switches, corrupt files, mutation-vs-sync ordering, lost updates |
| Storage security and privacy | IV/tag handling, key lifecycle, plaintext exposure, logging leaks, manifest surface, update-check trust, user/server strings reaching paths or intents |
| Background worker battery and network | Foreground-service lifetime, metered traffic, retry ladders that tighten, idle loops under a wakelock, enqueue churn, budgets |
| Process death and restoration | What survives rotation and process death, dialogs, in-flight mutations, intents mutated for state, leaked scopes |
| Test coverage gaps | Classes carrying recent logic with no direct tests; this one is a fixer, not a reviewer |

Before publishing, **re-read the lines behind the top three or four findings yourself** and
mark them verified; leave the rest attributed to the reviews. When two reviewers disagree, the
code decides.

## Report

Deliver the audit as a page the user can share, with the same shape each time so successive
audits are comparable: a summary strip (counts by severity, fixed count), a "work done more
than once" table when performance was in scope (that is usually the question behind the ask),
then one section per dimension with findings first and a short "checked and fine" list, then
what to fix first. `assets/report-template.html` carries the styling and structure used before;
keep its severity chips and the `fixed` chip so a later pass can flip states in place. Publish
with the Artifact tool when available; otherwise write the HTML next to the audit notes.

Say plainly what was not measured. Reasoning from code is not a device measurement.

## Fix mode (when asked)

Read `references/fix-mode.md` before dispatching fixes. The short version:

- One fixer per area in its **own worktree and branch** off the audited commit, with an explicit
  file-ownership list so two fixers never edit the same file. Overlaps go to exactly one of
  them; the other reports the snippet it needs and you apply it after merging.
- Every fixer runs the full check chain before reporting and never touches a device.
- Merge in the recommended order, run the full chain on the merged result, then **audit the
  fixed areas again** with fresh reviewers whose prompts list what was just changed. Expect
  regressions; fix those the same way.
- Check the PR's state before pushing follow-ups. A squash-merged PR means new work needs a
  rebase onto main and a new PR, not more commits on the old branch.

## Device work

Instrumented tests and timing on the user's phone are valuable and risky: by default the test
run uninstalls the app and its signed-in session, a locked phone fails every lifecycle test,
and a cold-start timing needs a signed-in account with a cached library. See `references/fix-mode.md` for the flags and checks. Report device results as what they are, including "not evaluated
because the keyguard was on".
