# Fix mode: turning findings into merged commits without stepping on anyone

## Worktrees and ownership

- One fixer per area, each in its own worktree and branch off the audited commit:
  `git branch -f fix/<area> <commit> && git worktree add <repos>/lw-<area> fix/<area>`, then
  copy `local.properties` (or whatever the build needs) into it. Never `git checkout` in the
  user's main checkout; another session may be using it.
- Give each fixer an explicit **file-ownership list** and the rule: do not edit files another
  fixer owns; describe the exact snippet needed in your report instead. Typical split for a
  media app: data layer (repos, cache, stores, storage); background pipeline (worker,
  scheduler, queue, downloads, coordinator); UI list/grid plus view model plus Application;
  detail viewer plus metadata; manifest and update checker with the UI fixer.
- Findings that span two areas go to exactly one fixer; put the cross-file snippet request in
  the report contract so the merge step knows to apply it.
- Fixers cannot receive messages mid-run. Anything you forgot goes into a follow-up agent in
  the same worktree after the first reports; reset the worktree to the merged head first.

## What every fixer prompt contains

1. The worktree path and branch, "do not touch other directories", the source root.
2. The repo rules from `CLAUDE.md` (warnings as errors, formatter, coverage gates, pure logic
   in small tested objects, where strings and diagnostics go).
3. The findings, numbered, in the order to fix them, each with file and line, what happens,
   and the intended fix; say the line numbers come from a review at commit X and the current
   code must be re-read first.
4. "Commit per item with a message explaining why; no attribution lines."
5. "After each item run the formatter and a compile; at the end run the full check chain and
   fix until green. NEVER run device tests or install on a device."
6. The report contract: commits with one line each, anything deliberately not done and why,
   exact snippets for changes needed in files it does not own, the check-chain result.

## Merge and verify

- Merge in the recommended order into a worktree on the target branch (again, not the main
  checkout). Apply the cross-file snippets the fixers handed back. Run the full check chain
  on the merged result, not just on each branch.
- Then re-audit the changed areas with fresh reviewers whose prompts list what was just
  fixed. The second pass has always found regressions; budget for a second fix round.
- Push, then confirm CI actually runs for the branch (many repos run CI only on
  pull_request to main and push to main). Before pushing follow-ups to an existing PR branch,
  check `gh pr view <n> --json state`; a squash-merged PR means the branch is dead: rebase the
  new commits onto main (`git rebase --onto origin/main <mergedHead>` into a new branch) and
  open a new PR. Merging main into the old branch conflicts on every later edit.
- Remove worktrees when done. On Windows, `git worktree remove --force` can fail on long
  build paths; delete with PowerShell `Remove-Item -LiteralPath "\\?\<path>" -Recurse -Force`
  and then `git worktree prune`.

## Instrumented tests and installs

- Gradle's connected test task uninstalls the app (and its data, including any signed-in
  session) after the run unless `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`
  is passed. Pass it, then `adb install -r` the assembled APK anyway.
- Lifecycle tests need an unlocked, awake phone; a secure keyguard cannot be dismissed from
  adb, and a screen that re-locks within seconds fails them with "activity never becomes
  RESUMED/DESTROYED" or "cannot perform this action after onSaveInstanceState". Check
  `dumpsys window | grep isKeyguardShowing` before blaming the code, and report such runs as
  not evaluated rather than as failures.
- With several transports for one device, set `ANDROID_SERIAL`.

## Before the push: what the JVM chain cannot see

- Run `scripts/scan-view-init-order.sh` (in the skill directory) over every Kotlin file under
  a UI package that a fixer touched. A hit means a constructor reads a field declared below
  its `init` block; move the declarations above the block.
- Re-fetch `origin/main` and compare against the audited commit. If it moved structurally,
  merge it into the integration branch first and re-run the chain there.
- After the PR is open, watch with `gh pr checks <n> --watch --interval 60` in a background
  shell. If only the device job fails, fetch the run's `device-tests-*` artifact
  (`gh run download <run-id> -n <artifact-name>`) and read the `TEST-*.xml` failures.
  Emulator errors before the line "Emulator booted" are infrastructure; anything after is code.
