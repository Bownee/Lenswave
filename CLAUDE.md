# Lenswave

Android viewer for Proton Photos. Kotlin, programmatic Views (no XML layouts, no Compose screens),
Hilt, Room + SQLCipher for the Proton Core session, WorkManager for thumbnail downloads, Media3 for
video, `me.proton.drive:sdk` for Drive access.

## Commands

```shell
./gradlew.bat --offline ktlintCheck testDebugUnitTest jacocoDebugCoverageVerification lintDebug compileDebugAndroidTestKotlin assembleDebug
```

Run `./gradlew.bat --offline ktlintFormat` after editing Kotlin; CI rejects formatting drift.

Debug APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Drop `--offline` (and add
`--write-locks --write-verification-metadata sha256 --no-configuration-cache`) only when a
dependency changed; see CONTRIBUTING.md.

## Layout

- `gallery/` — timeline, filters, albums: `GalleryActivity` + `GalleryScreen` are wiring;
  state comes from `GalleryViewModel` through `GalleryUiStateFactory`; navigation is
  `GalleryDestination` + `GalleryNavigationPolicy`.
- `viewer/` — full-screen photo/video viewer split into `Viewer*` controllers (media transform,
  swipe/peek, dismiss, details sheet, video playback).
- `proton/` — everything that talks to Proton: `ProtonPhotoGateway` is the session boundary; UI
  code depends on the narrow interfaces in `gallery/GalleryDataSources.kt`, not the gateway.
  Downloads: `ProtonOriginalDownloads` (full-size files) and `ProtonRenditionDownloads`
  (thumbnails, previews). Background work: `ProtonThumbnailWorker` drives `ProtonRenditionSync`,
  which claims from two `ProtonThumbnailQueue` instances (`@ThumbnailQueue`, `@PreviewQueue`);
  `ProtonBackgroundBatchPolicy` serves thumbnails first, previews wait for the charger unless the
  app is on screen, and a photo Proton has no preview for keeps its thumbnail as its preview.
  Stores: `ProtonThumbnailStore`, `ProtonPreviewStore`, `ProtonOriginalStore` (4 GiB LRU cap).
- `storage/` — envelope-encrypted files (Keystore-wrapped per-scope data key) and the database
  passphrase.
- `update/` — GitHub Releases update check.

## Rules

- Lint and Kotlin compiler warnings are errors. Coverage gates are enforced; `*Policy` objects
  must stay above 80 %.
- Pure logic goes in small objects with unit tests; Activities only wire things together.
- Keep `UiStyle` the single place for colours and view factories.
- Never zero the SQLCipher passphrase array; Room reads it lazily (see `ProtonDatabaseKeyMigration`).
- Strings in `res/values/strings.xml`; failures via `LenswaveDiagnostics.reportFailure`.
