# Lenswave

Android viewer for Proton Photos. Kotlin, programmatic Views (no XML layouts, no Compose screens),
Hilt, Room + SQLCipher for the Proton Core session, WorkManager for thumbnail downloads, Media3 for
video, `me.proton.drive:sdk` for Drive access.

## Commands

```shell
./gradlew.bat --offline testDebugUnitTest jacocoDebugCoverageVerification lintDebug compileDebugAndroidTestKotlin assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Drop `--offline` (and add
`--write-locks --write-verification-metadata sha256 --no-configuration-cache`) only when a
dependency changed; see CONTRIBUTING.md.

## Layout

- `gallery/` — timeline, filters, albums, trash: `GalleryActivity` + `GalleryScreen` are wiring;
  state comes from `GalleryViewModel` through `GalleryUiStateFactory`; navigation is
  `GalleryDestination` + `GalleryNavigationPolicy`.
- `viewer/` — full-screen photo/video viewer split into `Viewer*` controllers (media transform,
  swipe/peek, dismiss, details sheet).
- `proton/` — everything that talks to Proton: `ProtonPhotoGateway` is the session boundary; UI
  code depends on the narrow interfaces in `gallery/GalleryDataSources.kt`, not the gateway.
- `storage/` — Keystore-backed encrypted files and the database passphrase.
- `update/` — GitHub Releases update check.

## Rules

- Lint warnings are errors. Coverage gates are enforced; `*Policy` objects must stay above 80 %.
- Pure logic goes in small objects with unit tests; Activities only wire things together.
- Keep `UiStyle` the single place for colours and view factories.
- Never zero the SQLCipher passphrase array; Room reads it lazily (see `ProtonDatabaseKeyMigration`).
- Strings in `res/values/strings.xml`; failures via `LenswaveDiagnostics.reportFailure`.
