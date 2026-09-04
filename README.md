# Lenswave

Lenswave is an Android viewer for Proton Photos. Browse your cloud timeline, filter by media type,
open albums, and manage favourites and deletions from your phone. Full-size photos and
videos are kept in an encrypted on-device cache so they open instantly the second time.
Screen-sized previews of every photo are fetched in the background, so photos open sharp at once
while the original is still downloading.

Lenswave is an independent project and is not affiliated with Proton. The Proton integration uses
preview SDKs and may change.

## Install

Download the latest signed `arm64-v8a` APK from
[GitHub Releases](https://github.com/Bownee/Lenswave/releases/latest). Lenswave checks GitHub
Releases at startup and opens the release page only when you choose to view an available update.

Lenswave supports Android 10 (API 29) and later.

## Build

Requirements: JDK 21, Android SDK platform 36 and build-tools 36.0.0.

```shell
./gradlew testDebugUnitTest jacocoDebugCoverageVerification lintDebug lintRelease assembleDebug
```

Use `gradlew.bat` on Windows. Debug APKs are written to `app/build/outputs/apk/debug/`.
See [CONTRIBUTING.md](CONTRIBUTING.md) for the full check list, dependency pinning, and releases,
and [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## How it is put together

| Package | Role |
| --- | --- |
| `gallery/` | Timeline, media filters and albums; state built by `GalleryUiStateFactory` |
| `viewer/` | Full-screen photo and video viewer with swipe, zoom, dismiss and details sheet |
| `proton/` | Proton session boundary, sync repositories, encrypted caches, thumbnail worker |
| `storage/` | Android Keystore-backed encrypted files and the database passphrase |
| `update/` | GitHub Releases update check |

The UI is built from programmatic Views. Compose is present only because Proton Core's
presentation artifact requires an `AppTheme` binding.

## License

Lenswave is licensed under GPL-3.0-only. See [LICENSE](LICENSE).
