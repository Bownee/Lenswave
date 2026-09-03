# Changelog

All notable changes to Lenswave. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed
- The Proton session database is now keyed with the stored random passphrase. Earlier builds
  zeroed the passphrase before the database was opened, so it was effectively encrypted with a
  constant key. Existing databases are rekeyed once on first launch.
- Favourites toggled in the viewer now refresh the gallery when you return.
- Swiping quickly after a video became ready no longer shows a black frame.
- Cached-thumbnail counts and decrypted-cache cleanup no longer run on the main thread.

### Changed
- Proton-only: device photos, the editor and the picker were removed.
- New navigation: a pinned Photos | Albums switch with media-type filter chips under it, albums
  on the Albums tab, settings top right, no bottom bar. New visual design and icons.
- The drag handle appears on every photo grid while scrolling, with an equal gap above and below.
- Full-size photos and videos open from the encrypted cache without re-downloading; the cache
  has no size limit and is cleared on disconnect.
- Swiping between photos shows the neighbouring photo during the gesture.
- Build, CI and release tooling were reworked (version catalog, strict checksums, shared CI
  workflow, test reports and lint SARIF in pull requests).
- The gallery header no longer shows the photo or album count.

### Removed
- The Trash page and permanent deletion. Delete still moves photos to Proton Trash, where they
  can be recovered or removed for good.

## [0.19.10]

Last release before the Proton-only rework.
