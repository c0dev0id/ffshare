# Development Journal

## Software Stack

- **Language:** Kotlin (JVM target 1.8), Android views + ViewBinding. No Compose, no DI framework,
  no coroutines — ffmpeg's async callbacks are the concurrency model.
- **Build:** Gradle 9.5.0 wrapper, AGP 9.3.1, Kotlin 2.2.10. `minSdk 26`, `compileSdk`/`targetSdk 34`.
- **Media:** [ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next) v8.1.1, built locally by
  `build_ffmpegkit.sh` into `app/libs/` (not committed, not on Maven), plus `smart-exception-java`.
- **AndroidX:** appcompat, core-ktx, material, constraintlayout, navigation, preference,
  exifinterface, multidex.
- **Logging:** Timber, debug builds only.
- **Persistence:** SharedPreferences for settings, raw `SQLiteOpenHelper` for the ffmpeg log history.
- **Distribution:** F-Droid, GitHub releases, Obtainium. Store metadata under `fastlane/`.

## Key Decisions

- **Share target, not a media manager.** `HandleMediaActivity` handles `ACTION_SEND`/`SEND_MULTIPLE`
  and is the only real code path; `MainActivity`'s file picker synthesizes the same intent rather
  than duplicating logic.
- **Sequential compression via self-recursive callback.** `FFmpegKit.executeAsync` returns
  immediately, so `MediaCompressor.compressFiles` advances to the next file from the previous
  file's completion callback. A `for` loop would run every file concurrently and thrash the device.
- **SAF handles, never file paths.** Input URIs come from other apps, so ffmpeg addresses them via
  `FFmpegKitConfig.getSafParameterForRead/Write`. Those handles are single-use.
- **Output goes to a UUID cache dir, exposed by FileProvider.** Keeps the app off external storage
  permissions for writing and lets `CacheCleanUpReceiver` prune by directory age (12-hourly alarm,
  1-hour TTL, self-cancelling when empty).
- **Input MediaType and output MediaType are tracked separately.** Format conversion means the two
  differ; resolution scaling keys off the input type, codec/bitrate flags off the output type.
- **Preferences persist enum constant names.** `Settings` is the single accessor and `arrays.xml`
  entry values are the enum names verbatim. This makes preference XML and Kotlin enums one unit, at
  the cost of needing a migration in `SettingsVersionUpdater` (dispatched on `versionCode` ranges)
  whenever a constant is renamed.
- **Log history is disposable.** ffmpeg output is gzip+base64'd, pruned to 50 entries, and
  `onUpgrade` drops the table rather than migrating it.
- **Custom ffmpeg params bypass the builder.** `pref_custom_{video,image,audio}_params`, when set,
  are passed through verbatim — an escape hatch that deliberately skips all derived flags.
- **Rotation is handled by keeping the activity alive, not by saving state.** `MediaCompressor`
  drives the activity's views directly and `onStop` cancels ffmpeg, so a recreation mid-compression
  would abort and restart the batch. `HandleMediaActivity` therefore declares the rotation-related
  `configChanges`. Hoisting the compression into a ViewModel was considered and rejected: it would
  survive `onStop` too, so it would still need an `isChangingConfigurations` guard to preserve the
  deliberate "leaving the app cancels ffmpeg" behaviour, in exchange for inverting the whole
  view-driven compressor.
- **One adaptive layout per screen, no `layout-land/` duplicates.** Content scrolls inside a
  `NestedScrollView` with `fillViewport`, keeping the centred portrait look while staying reachable
  on short viewports. Height-sensitive values use qualifier resources rather than runtime checks.
  Duplicated landscape XML would mean every id and string edit had to be made twice.
- **ARM only.** `build_ffmpegkit.sh` disables x86/x86-64 to keep the AAR small; release ships a
  universal APK plus armeabi-v7a and arm64-v8a splits.

## Core Features

- Compress shared images, video and audio through ffmpeg, then re-share via a new share sheet.
- Batch handling of multiple shared files with progress (elapsed/total, percentage, running output
  size) and a cancel button.
- Format conversion per media class, with an option to treat GIFs as videos.
- Quality controls: video CRF, JPEG qscale, x264-style preset, video/audio codec selection,
  max image and video resolution, target maximum video file size (derived bitrate cap).
- Output naming: random UUID, original filename, or a fixed custom name.
- Optional exif tag copying for JPEG/PNG.
- ffmpeg command history with full output, viewable and copyable, toggleable and clearable.
- Localized into French, Spanish, Galician, Turkish and Chinese (Simplified and Traditional).
