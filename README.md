# Lenswave

Lenswave is an Android gallery and non-destructive photo editor. Browse photos stored on your device and in Proton Photos, then save edited copies to `Pictures/Lenswave`.

Lenswave is an independent project and is not affiliated with Proton. The Proton integration uses preview SDKs and may change.

## Install

Download the latest signed `arm64-v8a` APK from [GitHub Releases](https://github.com/Bownee/Lenswave/releases/latest).
Lenswave periodically checks GitHub Releases at startup and opens the release page only when you choose to view an available update.

Lenswave supports Android 10 (API 29) and later.

## Build

Requirements: JDK 21, Android SDK 36, and Android build tools 36.0.0.

```shell
./gradlew testDebugUnitTest jacocoDebugCoverageVerification lintDebug assembleDebug assembleMinified
```

Use `gradlew.bat` on Windows. Debug APKs are written to `app/build/outputs/apk/debug/`.

## License

Lenswave is licensed under GPL-3.0-only. See [LICENSE](LICENSE).
