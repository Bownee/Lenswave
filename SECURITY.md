# Security policy

Lenswave handles end-to-end encrypted photos and a Proton session. Please report vulnerabilities
privately rather than in a public issue.

## Reporting

Use GitHub's private vulnerability reporting on this repository
(Security tab, "Report a vulnerability"). Include the app version from Settings, the Android
version, and steps to reproduce. You should hear back within seven days.

## Supported versions

Only the latest release on the [releases page](https://github.com/Bownee/Lenswave/releases/latest)
receives fixes.

## What the app protects on the device

- The Proton session database is SQLCipher-encrypted with a random 32-byte passphrase that is
  itself wrapped by an Android Keystore key (`storage/SecureFileStore.kt`).
- Cached thumbnails and originals are stored encrypted with per-account Keystore keys and are
  erased when the account disconnects. Decrypted copies used for viewing live in the cache
  directory for at most 30 minutes.
- Nothing is uploaded anywhere except Proton's own API; telemetry is opt-in and off by default.

## Supply chain

Every dependency is pinned by checksum in `gradle/verification-metadata.xml`, GitHub Actions are
pinned by commit SHA, releases ship a CycloneDX SBOM, and CodeQL runs weekly.
