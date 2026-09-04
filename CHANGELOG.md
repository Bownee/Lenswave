# Changelog

All notable changes to Lenswave. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Previews (~1920 px) of every photo are downloaded in the background after thumbnails, so
  photos open sharp immediately.
- Pull down on any photo grid or the album list to refresh it from Proton.
- While the gallery is open it re-enumerates the visible section quietly about every 15
  minutes (the album list every 30). Returning from the background triggers an immediate check.
- Cached originals are capped at 4 GiB per account; the oldest are removed first.
- Battery: the download worker no longer sleeps inside its foreground service for more than two
  minutes waiting on retries, gives up on a photo after six failed attempts until the next sync,
  re-posts its notification at most every 1.5 seconds, keeps one network callback per run instead
  of one per batch, and previews wait for the charger unless the app is on screen (a
  charging-only run picks them up). The viewer prefetches the next original only in the direction
  you are swiping.

### Fixed
- The Proton session database is now keyed with the stored random passphrase. Earlier builds
  zeroed the passphrase before the database was opened, so it was effectively encrypted with a
  constant key. Existing databases are rekeyed once on first launch.
- Favourites toggled in the viewer now refresh the gallery when you return.
- Swiping quickly after a video became ready no longer shows a black frame.
- Cached-thumbnail counts and decrypted-cache cleanup no longer run on the main thread.
- Photos that have no preview on Proton keep their thumbnail as their preview, so the preview
  download no longer restarts and stalls on them after every launch.
- A preview batch the SDK stops answering now ends after 15 seconds of silence instead of
  90, and the unanswered photos are asked for one by one straight away rather than being
  parked in a retry backoff of up to 15 minutes. Re-checking 195 preview-less photos takes
  about three minutes instead of stalling.
- The gallery opens with the cached photos straight away instead of a "Loading metadata" panel;
  the sync then runs quietly. Opening the cache took several seconds because file names were
  hex-encoded byte by byte with `String.format`; it now takes a fraction of a second.
- Cached files are encrypted with a per-account data key that the Android Keystore wraps,
  instead of running every byte through the Keystore cipher. Existing files are converted as
  they are read.

### Changed
- Internal: originals, renditions and background sync are separate classes; video playback has
  its own viewer controller; Kotlin compiler warnings fail the build like lint warnings do.
- Proton-only: device photos, the editor and the picker were removed.
- New navigation: a pinned Photos | Albums switch with media-type filter chips under it, albums
  on the Albums tab, settings top right, no bottom bar. New visual design and icons.
- The drag handle appears on every photo grid while scrolling, with an equal gap above and below.
- Full-size photos and videos open from the encrypted cache without re-downloading; the cache
  has no size limit and is cleared on disconnect.
- Swiping between photos shows the neighbouring photo during the gesture.
- Build, CI and release tooling were reworked (version catalog, strict checksums, shared CI
  workflow, test reports and lint SARIF in pull requests); ktlint enforces formatting.
- The gallery header no longer shows status text (counts, loading, or thumbnail progress).

### Removed
- The Trash page and permanent deletion. Delete still moves photos to Proton Trash, where they
  can be recovered or removed for good.

## [0.19.10]

Last release before the Proton-only rework.
