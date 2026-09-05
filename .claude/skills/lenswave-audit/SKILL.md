---
name: lenswave-audit
description: Lenswave-specific companion to the app-audit skill: the area file map, check chain, state locations, git conventions and audit history reviewers and fixers need in this repo. Use it together with app-audit whenever auditing, reviewing, or fixing performance, data safety, security, battery, or restoration issues in Lenswave, and when re-running the audits.
---

# Lenswave audit companion

Read this alongside the `app-audit` skill; it supplies the repo facts that skill leaves to
the project. Nothing here replaces `CLAUDE.md`, which stays the source for build rules.

## Check chain (run before any push)

```
./gradlew.bat --offline ktlintFormat
./gradlew.bat --offline ktlintCheck testDebugUnitTest jacocoDebugCoverageVerification lintDebug lintRelease compileDebugAndroidTestKotlin assembleDebug
```

Lint and compiler warnings are errors; `*Policy` objects need >80 % coverage; pure logic
goes in small tested objects; strings via `res/values/strings.xml`; failures via
`LenswaveDiagnostics.reportFailure` (it logs through `android.util.Log`, which throws in JVM
tests, so classes under test take a reporter seam); never zero the SQLCipher passphrase
array; `UiStyle` is the single source of colours and view factories.

## Area map for reviewers and fixers

Sources live in five Gradle modules that share the Kotlin package `com.bownee.lenswave`: `core/`,
`storage/`, `update/`, `proton/`, and `app/` (gallery, viewer), each under
`<module>/src/main/kotlin/com/bownee/lenswave/`. List each directory before handing a
reviewer its files; names drift.

| Area | Where |
|---|---|
| Data layer and storage | `proton/` cache, stores, repositories, snapshot sync and policies, gateway, session guard, account session and transition, client provider, Core database and key migration; `storage/` (secure file store, segmented envelope, atomic file store, passphrase store) |
| Gallery UI | `gallery/` (activity, screen, view model, UI-state factory, adapter, list view, cells, grouping, thumbnail loader, fast scroller, sticky date, scroll and navigation stores, memo and render policies, deletion coordinator, settings and update presenters, periodic sync policy), `UiStyle.kt`, `LenswaveApplication.kt` |
| Download pipeline | `proton/` worker, work scheduler and follow-up, run guard, enqueue and pause policies, queue plus flush/selection/merge policies, background batch policy, network monitor, foreground info factory, failure classifier, rendition downloads and sync, original downloads and shared download, original stream and progress policy, progressive data source, transfer coordinator, work names |
| Viewer | `viewer/` (activity, screen, full-resolution view, base and detail decode policies, request, navigation sources and provider and window policy, swipe, details sheet, video and mutation controllers, privacy settings), `metadata/`, `ExifOrientation.kt` |
| Security surface | `storage/`, `LenswaveDiagnostics.kt`, `update/`, `AndroidManifest.xml`, `res/xml/` |

## Where state lives (for restoration reviews)

Destination in `SavedStateHandle` with the codec in `GalleryNavigationStore.kt`; tab root in
the `gallery-navigation` preferences; scroll and selection in `GalleryViewModel` plus saved
state; viewer position, window, zoom, sheet and video state in
`PhotoViewerActivity.onSaveInstanceState`; the in-process navigation list in
`PhotoNavigationSources` keyed by token and rebuilt by `PhotoNavigationSourceProvider` after
process death.

## Git and CI conventions

- PRs are squash-merged. Before pushing follow-ups to a PR branch, check
  `gh pr view <n> --json state`; after a squash, rebase the new commits onto `origin/main`
  into a new branch and open a new PR.
- CI (`Android CI`, `Security`) runs only on pull_request to main and push to main, never on a
  plain branch push. Main requires green checks; no auto-merge.
- Several sessions may share the checkout. Work in worktrees; never switch branches in the
  main checkout.

## Device tests

Instrumented tests uninstall the app and its data unless
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` is passed; a locked phone
fails the lifecycle tests in `GalleryActivityStartupTest` without any code being wrong. Device
identifiers are deliberately not recorded here; the CI emulator job is the reference run.

## Audit history

- PR #12 (2026-09-04): cold start, gallery, downloads and viewer performance, two passes.
- PR #22: sync safety, segmented file encryption (format v3), worker budget and pause,
  state restoration, security lows, gateway and view-model tests.

A new audit should list these as already fixed so reviewers look for what is still there or
newly introduced.
