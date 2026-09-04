# Contributing to Lenswave

## Requirements

- JDK 21 (CI pins the exact build in `.github/toolchain/java-version`)
- Android SDK platform 36 and build-tools 36.0.0 (`.github/toolchain/android-sdk-packages`)
- A device or emulator on Android 10 (API 29) or later for instrumented tests

## Everyday checks

This is what CI runs on every pull request. Run it before pushing:

```shell
./gradlew ktlintCheck testDebugUnitTest jacocoDebugCoverageVerification lintDebug assembleDebug
```

`ktlintCheck` fails on formatting drift; `./gradlew ktlintFormat` fixes it in place.

On Windows use `gradlew.bat`. The release build and the license inventory need one extra invocation
because the SBOM task is not configuration-cache compatible:

```shell
./gradlew assembleRelease verifySbomLicenses --no-configuration-cache
```

Instrumented tests run against the debug build by default. To exercise the R8-optimised build:

```shell
./gradlew connectedMinifiedAndroidTest -Plenswave.instrumentationBuildType=minified
```

Add `-Plenswave.includeX86TestAbi=true` when the target is an x86_64 emulator.

## Dependencies

All versions live in `gradle/libs.versions.toml`. Two generated files pin what actually resolves and
are enforced strictly:

- `app/gradle.lockfile` and `settings-gradle.lockfile` (Gradle dependency locking)
- `gradle/verification-metadata.xml` (SHA-256 checksums for every artifact)

After changing any dependency, regenerate both in one go and commit the result:

```shell
./gradlew --write-locks --write-verification-metadata sha256 testDebugUnitTest lintDebug assembleDebug --no-configuration-cache
```

Dependabot opens version bumps but cannot regenerate these files, so every dependency PR needs this
step before it goes green. Review the license of any new artifact: `verifySbomLicenses` fails on
licenses outside the approved list in `build.gradle.kts`.

## Coding conventions

- ktlint's official code style, enforced by `ktlintCheck` and configured in `.editorconfig`;
  120-column lines.
- The UI is built from programmatic Views. Compose exists only because Proton Core's presentation
  artifact needs an `AppTheme` binding (`LenswaveTheme.kt`); do not add Compose screens.
- Put decisions in small pure objects (`*Policy`, `*Formatter`, `*Codec`) and unit-test them; keep
  Activities and Screens as wiring. Coverage for `*Policy` classes must stay above 80 percent.
- Lint warnings and Kotlin compiler warnings fail the build. Prefer fixing the cause over suppressing.
- Every user-visible string goes through `res/values/strings.xml`.
- Report failures through `LenswaveDiagnostics` with a `LenswaveOperation`, never `Log.e` directly.

## Releases

1. Bump `VERSION_NAME` and `VERSION_CODE` in `version.properties` on `main`.
2. Add the release notes to `CHANGELOG.md`.
3. Create an annotated tag `vX.Y.Z` on the reviewed tip of `main` and push it.

The release workflow verifies provenance, re-runs the full checks, builds the unsigned APK and
SBOM, signs in the protected `production-signing` environment, and publishes the GitHub release.
