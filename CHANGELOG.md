# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release notes for versions up to and including 2.0.0 predate this file and live in
`fastlane/metadata/android/en-US/changelogs/`, keyed by version code.

## [Unreleased]

### Added

- The compression screen now shows a remaining-time estimate next to the progress
  readout, derived from ffmpeg's own encode-speed metric.
- Compression now keeps running when you leave the app. A notification reports progress and can
  cancel the run.
- "Check for updates" button on the main screen fetches the latest dev build from GitHub and
  installs it, verified against the checksums published with the release. The previously
  installed APK is deleted from cache when the app next starts.
- Compressed files are kept after the run finishes. A **Share** button sends them to another app;
  a **Done** button deletes them and stops the service. Sharing can be retried if it fails.
- The result notification now opens the app to show compression stats rather than jumping
  straight to the share sheet. Tapping Done from the app dismisses the notification and cleans up.
- Landscape orientation is now supported on every screen. The compression screen was previously
  locked to portrait; it now rotates without interrupting the ffmpeg job in progress.
- Settings has a shortcut to the system battery-optimization screen, for devices whose vendor cuts
  background work short.

### Fixed

- The main screen and the compression screen no longer clip their contents when the screen is
  short. Both scroll instead, and the select-file button stays visible rather than being crowded
  by the introduction text.
- The log list is bounded to the screen and scrolls to its last entry instead of running off the
  bottom.
- The log detail dialog sizes itself to its contents and shrinks its output box on short screens,
  so the copy button stays reachable.
- Cancelling a compression no longer cancels unrelated ffmpeg work, and a failed file no longer
  starts the next one while ending the batch at the same time.
- Videos that report no duration no longer show an infinite progress percentage.
- Sharing something new straight after a compression no longer shows the previous run's result
  and send its files instead of compressing what was just shared. Cancelling a run while the app
  was in the background no longer closed the next share the moment it opened.
- A batch that stops part way now shows the sizes of the files that did compress, with the
  reason the rest did not, instead of only the error.
- Denying the storage permission now says so instead of failing later with a generic error.
- Starting a new compression clears the previous run's "Ready to share" notification and its
  leftover files, rather than leaving both behind.
- The progress bar no longer stutters on long videos; the notification was being rebuilt faster
  than Android will accept updates.
- Update failures now report what went wrong rather than showing a bare URL, and a download cut
  short is discarded instead of being handed to the installer as a valid APK.
- Removed the "show status messages" setting. It never worked — it was written to one key and
  read from another — and the compression result is now always shown on screen and in the
  finished notification, so there is nothing left for it to switch off.

[Unreleased]: https://github.com/caydey/ffshare/compare/v2.0.0...HEAD
