# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release notes for versions up to and including 2.0.0 predate this file and live in
`fastlane/metadata/android/en-US/changelogs/`, keyed by version code.

## [Unreleased]

### Added

- Landscape orientation is now supported on every screen. The compression screen was previously
  locked to portrait; it now rotates without interrupting the ffmpeg job in progress.

### Fixed

- The main screen and the compression screen no longer clip their contents when the screen is
  short. Both scroll instead, and the select-file button stays visible rather than being crowded
  by the introduction text.
- The log list is bounded to the screen and scrolls to its last entry instead of running off the
  bottom.
- The log detail dialog sizes itself to its contents and shrinks its output box on short screens,
  so the copy button stays reachable.

[Unreleased]: https://github.com/caydey/ffshare/compare/v2.0.0...HEAD
