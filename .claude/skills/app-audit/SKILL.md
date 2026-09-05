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

## The companion skill this method expects

This file is the method and knows nothing about any one repository. It expects a companion
skill named `<app>-audit` in the repo's `.claude/skills/` and reads it before anything else.
The companion must supply, in this order so prompts can be assembled from it top to bottom:

1. **Check chain**: the exact commands a fixer runs before reporting, plus the repo rules that
   are not derivable from the code (warnings as errors, coverage gates, where strings and
   diagnostics go, anything that must never be done).
2. **Area map**: for each reviewer area, the source root and the directories or files it owns.
   Reviewers get files, not area names, so the map must be concrete enough to list them.
3. **Where state lives**: what the restoration reviewer traces (saved state, preferences,
   in-memory registries) and which class owns each.
4. **Git and CI conventions**: merge policy, whether CI runs on plain branches, which job is the
   device gate, worktree and squash-merge rules for parallel sessions.
5. **Device tests**: how to run them without wiping the install, what a locked phone does to
   them, and what to report as "not evaluated".
6. **Audit history**: every earlier pass with what it fixed and what it deferred, so reviewers
   look for what is still there or newly introduced instead of re-reporting closed items.

If the companion is missing, write it from `CLAUDE.md` and `CONTRIBUTING.md` before the first
reviewer is spawned, and keep it short and factual: the method belongs here, the facts belong
there. When an audit ends, the companion's history is the one thing that must be updated.

## Before you start

- Establish the exact commit under review and put a **detached, read-only worktree** at it.
  Reviewers read from there so concurrent edits (yours or another session's) cannot shift line
  numbers under them. Use a short path such as `<repos>/lw-audit` on Windows: long paths break
  `git worktree remove`.
- Read the repo's `CLAUDE.md` / `CONTRIBUTING.md` for the check chain, the coding rules and the
  layout; reviewers need the file map, fixers need the rules.
- Read the `<app>-audit` companion (see "The companion skill this method expects" above) before
  writing a single reviewer prompt; every prompt below draws its file lists, rules and history
  from it.

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

Before pushing, run `scripts/scan-view-init-order.sh` over every view class a fixer touched;
see "Lessons from the third audit" below for why.

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

## Lessons from the third audit (2026-09-05: three parallel audits, three fix rounds)

Three sessions audited the same commit independently and each found about seventy issues
with only partial overlap. Single-pass line-by-line review has recall well below 100 %, so:

- **Treat a second independent audit as part of the method**, not a duplicate. After fix
  mode, verify the other audit's findings against the merged result with a read-only agent
  that classifies each as fixed / still present / by design, then run a fix round on what
  is left. The questions that produced the third round are in `references/dimensions.md`
  under "Checks earlier passes missed"; hand them to the reviewers from the start.
- **The second pass must ask "did the fix close every path", not "does the fix hold".** Two
  first-round fixes each closed one of two paths (the unreferenced sweep but not the reconcile
  loop; the settings dialogs but not their writes) and read as holding.
- **The first fix round introduces regressions of its own.** Round one here added a
  non-terminating worker loop and a cache wipe on a not-yet-loaded account; the second-pass
  reviewers found both. Budget for the second round every time.
- **JVM checks cannot construct Views.** A property declared after an `init` block that read
  it passed the whole local chain and crashed every gallery start on the CI emulator. Run
  `scripts/scan-view-init-order.sh` over every view class a fixer touched, and treat the
  device job as part of the gate, not a flaky extra. When it fails, download the run's
  test-result artifact and read the XML; the `--log-failed` excerpt rarely names the test.
- **Main moves while fixers run.** Between the audited commit and the PR, main gained a
  module split, its revert and three CI changes. `git fetch` and diff `origin/main` against
  the audited commit before every merge step, and merge main into the integration branch
  before dispatching the next round, not only at the start.
- **Fixer agents stall on the full check chain** (a 600 s watchdog). Their worktree commits
  survive; inspect `git log` and `git status` there and launch a follow-up agent for the
  remaining items and the report instead of redoing the work.
- **Cross-fixer APIs.** When fixer A needs a method on a class fixer B owns, have B add the
  method (keeping old APIs) while A ships a temporary local guard; wire the call at merge
  time. The reports' snippet section is where this hand-off lives.
- **Watch CI with something that terminates**: `gh pr checks <n> --watch` in a background
  shell. A polling monitor that swallowed `gh` errors sat silent for an hour, twice.
