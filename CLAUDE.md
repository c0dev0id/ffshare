# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

FFShare (`com.caydey.ffshare`) is an Android share-target app: the user shares image/video/audio
from any app, FFShare re-encodes it with ffmpeg, then re-shares the smaller file through a new
share sheet. Kotlin, views + ViewBinding, no DI framework. Compression runs in a foreground
service driven by coroutines; the UI only observes it.

## Build

The ffmpeg-kit AAR is **not** in the repo and is not fetched from a Maven repo. `app/build.gradle`
declares `implementation(files('libs/ffmpeg-kit-next-8.1.1.aar'))`, and `app/libs/` is gitignored.
Nothing builds until it exists:

```sh
./build_ffmpegkit.sh            # clones arthenica/ffmpeg-kit-next @ v8.1.1, builds, drops the AAR in app/libs/
```

That script takes hours: it is a full ffmpeg cross-compile for two ABIs with ~25 external
libraries, driven by Nix (`nix-android.sh`), whose `android-r27d` profile supplies the NDK. Version
is pinned in three places that must stay in sync: `FFMPEG_KIT_TAG_VERSION` and the `mv` path in
`build_ffmpegkit.sh`, and the `files(...)` filename in `app/build.gradle`.

CI does not build it. The `ffmpeg-kit` workflow is `workflow_dispatch` only; run it by hand when the
pinned tag changes or its artifact lapses (90 days), and every Gradle job then pulls the AAR from
its most recent successful run via `.github/actions/ffmpeg-kit-aar`.

`signingConfigs.release` reads `FFSHARE_RELEASE_STORE_FILE`, `FFSHARE_RELEASE_STORE_PASSWORD`,
`FFSHARE_RELEASE_KEY_ALIAS`, `FFSHARE_RELEASE_KEY_PASSWORD` as Gradle properties, guarded by
`hasProperty` so unsigned tasks work without them. `assembleRelease` needs all four, from
`~/.gradle/gradle.properties` or `-P` flags.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest                                         # JVM unit tests
./gradlew testDebugUnitTest --tests "com.caydey.ffshare.CompressionStateTest"  # single test
./gradlew connectedDebugAndroidTest                                 # instrumented, needs a device
./gradlew lint                                                      # report at app/build/reports/lint-results-*.html
./github_build_release.sh [preReleaseVersionName]                   # assembleRelease + github_releases/<version>/ with apk, changelog, sha256
```

`Build` runs lint and tests on pull requests targeting master; the signing and dev-release jobs run
only on pushes to master.

`build_ffmpegkit.sh` passes `--disable-x86 --disable-x86-64`, so the AAR has no x86 libs even
though the `debug` build type lists `x86`/`x86_64` in `abiFilters`. Emulators must be arm.

`./generate_test_files.sh` produces `test_files/` with deliberately awkward media (1x1 and 2x512
videos, odd dimensions, zero-duration video, exotic image formats) — the cases that break the
scaling and bitrate math. Needs `ffmpeg`, `magick`, `wget`.

## Flow

`HandleMediaActivity` is the entry point (`ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `image/*`,
`video/*`, `audio/*`). `MainActivity` is an info screen whose file picker synthesizes an
`ACTION_SEND_MULTIPLE` intent into it, so both paths converge.

**Nothing about the compression belongs to the activity.** `CompressionService` (foreground,
`dataSync` type) owns the run and publishes `CompressionState` on a process-wide `StateFlow`;
`HandleMediaActivity` starts a run and then only renders whatever that flow says. This is why
leaving the app, rotating, or killing the activity does not touch ffmpeg, and why the activity can
be reopened from the notification to watch a run it never started. Keep it that way — any
`Activity`, `View` or `findViewById` reaching into the compression path re-creates the old coupling.

`MediaCompressor.compress(inputs)` returns a `Flow<CompressionState>` and walks the batch in a plain
`for` loop; each `FFmpegKit.executeAsync` is awaited through `suspendCancellableCoroutine`.
Cancelling the collecting coroutine cancels *that session by id* — not every ffmpeg session in the
process. A file that fails ends the batch, but the files that already succeeded still reach the
terminal `Finished` state.

Per file, `compressOne`:
1. `Utils.getMediaType` — extension lookup first, magic-byte signature sniff as fallback.
2. `Utils.getCacheOutputFile` — decides the *output* MediaType from the conversion settings and
   returns `cacheDir/media/<random-UUID>/<name>.<ext>`.
3. `FFprobeKit.getMediaInformation` for size/duration. Null duration/size on a non-image is a hard
   error; a zero duration just means no progress readout.
4. `FFmpegParamMaker.create(...)` builds the argument string.
5. `FFmpegKit.executeAsync` with SAF parameters, then optional exif copy, then a row in the log DB.

Input and output are always addressed via `FFmpegKitConfig.getSafParameterForRead/getSafParameterForWrite`,
never file paths, and those handles are **single-use** — request a fresh one per command.

Everything blocking runs on `Dispatchers.IO`; the service collects on `Dispatchers.Main.immediate`,
so state updates and notifications need no `Handler` posting.

### Finishing

A finished run has to reach a share sheet, and **an activity cannot be launched from the
background** (Android 10+). So the outcome is delivered one of two ways, decided by
`ProcessLifecycleOwner`:

- app in the foreground → the activity sees `Finished`, opens the chooser itself, and consumes the
  state.
- app in the background → the service posts a notification whose content intent *is* the chooser,
  then resets the state to `Idle` because the notification now owns the outcome.

Output URIs go out through `FileProvider` (authority `com.caydey.ffshare.fileprovider`, mapped to
the `media/` cache dir by `res/xml/filepaths.xml`). `Utils.createShareChooser` builds the chooser for
both routes — keep it single-sourced, the grant flags are easy to get wrong.

## Input type vs. output type

`FFmpegParamMaker.create` receives both `mediaType` (what came in) and `outputMediaType` (what the
conversion settings ask for) and branches on them for different reasons — resolution scaling reads
the *input* type, codec/crf/bitrate flags read the *output* type. Mixing them up silently produces
wrong flags for conversions. Existing format-specific carve-outs to preserve: no `-preset` for
webp, no `-maxrate`/`-bufsize` for webm, `-2` pixel rounding (not `-1`) when scaling for H.264/H.265,
and a `crop=trunc(iw/2)*2:...` fallback when H.26x gets odd dimensions without scaling.

Setting a custom params string for a media class (`pref_custom_*_params`) bypasses this builder
entirely and is returned verbatim.

## Settings and migrations

`utils/Settings.kt` is the single accessor for every preference: one typed property plus one key
constant per setting, mirrored by an entry in `res/xml/preferences.xml` (and `res/values/arrays.xml`
for list preferences). Adding a preference means touching all three.

Enum-valued preferences (`Utils.MediaType`, `Settings.VideoCodecOpts`, `Settings.AudioCodecOpts`)
persist the **enum constant name** as a string, and `arrays.xml` entry values are those same names.
Renaming or removing a constant orphans stored preferences on upgrade, so it requires a migration in
`SettingsVersionUpdater`, which runs from `App.onCreate` and dispatches on the `versionCode` range a
user is upgrading from (see the existing MiB→KiB and `LIBX264`→`H264` migrations for the pattern).
`UNKNOWN` is the sentinel for "keep the original format", not an error.

## Logs

`utils/logs/LogsDbHelper` is a raw `SQLiteOpenHelper`. ffmpeg output is gzipped and base64'd before
storage; logs are pruned to `MAX_LOGS` (50) once the count exceeds 60. `onUpgrade` drops and
recreates the table — log history is disposable by design. Writes are gated on `settings.saveLogs`.
Timber is only planted in debug builds.

## Cache

Compressed files live in the app cache under `media/<UUID>/`. `CompressionService` schedules a
12-hourly inexact alarm when a run ends; `CacheCleanUpReceiver` deletes entries older than one hour
and cancels its own alarm once the directory is empty.

## Release

Bump `versionCode` and `versionName` in `app/build.gradle`, then add
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` — `github_build_release.sh` looks the
changelog up by version code and fails to produce release notes without it. The APK shipped to
GitHub is the universal one (`app-universal-release.apk`); ABI splits also produce per-arch APKs for
armeabi-v7a and arm64-v8a.

Translations live in `res/values-{fr,es,gl,tr,zh-rCN,zh-rTW}/`; only base, `fr` and `zh-rCN` also
carry `arrays.xml`, so new list-preference entries fall back to English elsewhere.

## Layouts

Every screen rotates. Rather than duplicating XML under `layout-land/`, each screen uses one layout
that adapts: content sits in a `NestedScrollView` with `fillViewport="true"` over a vertical
`LinearLayout` with `gravity="center_vertical"`, so it keeps its centred look when it fits and
scrolls when the viewport is short. Persistent chrome (the toolbar, the select-file button) stays
outside the scroll view so it cannot scroll out of reach. Height-dependent sizes come from
qualifier resources (`values-h600dp/dimens.xml`), not from orientation checks in code.

`processedTableRow` must stay a `TableRow` — `HandleMediaActivity` casts it.
