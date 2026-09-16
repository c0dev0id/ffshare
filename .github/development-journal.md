# Development Journal

## Software Stack

- **Language:** Kotlin (JVM target 1.8), Android views + ViewBinding. No Compose, no DI framework.
  Coroutines + Flow drive compression; androidx.lifecycle supplies `repeatOnLifecycle` and
  `ProcessLifecycleOwner`.
- **Build:** Gradle 9.5.0 wrapper, AGP 9.3.1, Kotlin 2.2.10. `minSdk 26`, `compileSdk`/`targetSdk 34`.
- **Media:** [ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next) v8.1.1, built locally by
  `build_ffmpegkit.sh` into `app/libs/` (not committed, not on Maven), plus `smart-exception-java`.
- **AndroidX:** appcompat 1.7, core-ktx 1.13.1, material 1.12, constraintlayout 2.2,
  preference 1.2.1, exifinterface 1.3.7. Navigation and multidex removed (unused / redundant).
- **Logging:** Timber, debug builds only.
- **Persistence:** SharedPreferences for settings, raw `SQLiteOpenHelper` for the ffmpeg log history.
- **Distribution:** F-Droid, GitHub releases, Obtainium. Store metadata under `fastlane/`.

## Key Decisions

- **Share target, not a media manager.** `HandleMediaActivity` handles `ACTION_SEND`/`SEND_MULTIPLE`
  and is the only real code path; `MainActivity`'s file picker synthesizes the same intent rather
  than duplicating logic.
- **Sequential compression through a suspending wrapper.** `FFmpegKit.executeAsync` returns
  immediately, so each session is awaited with `suspendCancellableCoroutine` and the batch is a
  plain `for` loop. This replaced a self-recursive completion callback, whose failure path both
  started the next file and ended the batch at once.
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
- **Compression lives in a foreground service, not in the activity.** The activity starts a run
  and then only renders `CompressionState`. This replaced an earlier `configChanges` workaround
  that merely stopped rotation from destroying the activity; owning the work outside the UI solves
  rotation, backgrounding and activity death at once. A ViewModel was rejected: it dies with the
  task and still could not keep ffmpeg running once the user left.
- **State is a process-wide `StateFlow` on the service's companion, not a binder.** Only one run
  happens at a time and a recreated activity only needs the latest value, so binding would be
  ceremony. It also means the activity can render a run it never started.
- **Battery-optimization exemption is offered, never requested.** A foreground service is already
  exempt from Doze and App Standby while it runs; the exemption only helps against vendor
  task-killers. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is Play-restricted to app categories this is
  not, so settings link out to the system screen instead.
- **A finished run is kept alive until the user explicitly dismisses it.** The service stays alive
  after compression finishes; `ACTION_DONE` deletes output files, cancels the result notification,
  and stops the service. `onDestroy` also cleans up if the service is killed while state is
  `Finished`. The result notification opens the activity (not the chooser); the activity's **Share**
  button does not consume state so the user can retry if sharing fails.
- **No background activity launch.** Android 10+ blocks it; the result notification always opens
  the activity (`FLAG_ACTIVITY_CLEAR_TOP`), which then provides the share button in its own UI.
- **CI never builds the ffmpeg-kit AAR.** ffmpeg-kit-next publishes no artifacts anywhere, and the
  build is a multi-hour Nix cross-compile, so it has a manual `workflow_dispatch` workflow and the
  Gradle jobs pull the artifact from its last successful run.
- **One adaptive layout per screen, no `layout-land/` duplicates.** Content scrolls inside a
  `NestedScrollView` with `fillViewport`, keeping the centred portrait look while staying reachable
  on short viewports. Height-sensitive values use qualifier resources rather than runtime checks.
  Duplicated landscape XML would mean every id and string edit had to be made twice.
- **ARM only.** `build_ffmpegkit.sh` disables x86/x86-64 to keep the AAR small; release ships a
  universal APK plus armeabi-v7a and arm64-v8a splits.

## Core Features

- Compress shared images, video and audio through ffmpeg, then re-share via a new share sheet.
- Compression continues in the background; a notification reports progress, cancels the run, and
  hands over the share sheet once it finishes.
- Batch handling of multiple shared files with progress (elapsed/total, percentage, running output
  size) and a cancel button.
- Format conversion per media class, with an option to treat GIFs as videos.
- Quality controls: video CRF, JPEG qscale, x264-style preset, video/audio codec selection,
  max image and video resolution, target maximum video file size (derived bitrate cap).
- Output naming: random UUID, original filename, or a fixed custom name.
- Optional exif tag copying for JPEG/PNG.
- ffmpeg command history with full output, viewable and copyable, toggleable and clearable.
- Localized into French, Spanish, Galician, Turkish and Chinese (Simplified and Traditional).
