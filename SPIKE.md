# Milestone 0: rendering spike

The spike answers one question per platform: which way of drawing a 1,000,000 × 50 grid with a frozen header and a frozen first column scrolls best on real phones. Nothing here is the v1 API.

## What's in the spike

Every candidate draws the same cells (88 × 32 pt, 36 pt header, 1 frozen column, numbers right-aligned with 2 decimals and thousands separators, negatives in red, striped rows) from the same native column store.

| | Candidate | Where |
|---|---|---|
| iOS A | `UICollectionView` with a custom layout that pins the header row and frozen columns. One section, item = row × columns + col. Cells draw their text in `draw(_:)`. | `ios/Spike/CollectionGridRenderer.swift` |
| iOS B | An empty `UIScrollView` for gestures and physics. Cells are pooled `CALayer`s drawn with UIKit string drawing, placed in four non-scrolling clip containers (corner, header, frozen column, body). Layer positions are viewport-relative. | `ios/Spike/LayerGridRenderer.swift` |
| Android A | One `View` that draws visible cells on a `Canvas`. Own touch handling, `VelocityTracker`, `OverScroller` fling, `EdgeEffect` on four sides. | `android/.../spike/CanvasGridView.kt` |
| Android B | Two vertically synced `RecyclerView`s (frozen column, body), body inside a `HorizontalScrollView`, one `TextView` per cell. | `android/.../spike/RecyclerGridView.kt` |
| Android C | Compose `LazyLayout` composing only visible cells (`Box` + `BasicText`), with a `scrollable` per axis. | `android/.../spike/ComposeGridView.kt` |

Shared pieces:

- `GridData` shared object (`ios/GridData.swift`, `android/.../GridData.kt`). Columns live in native memory. `fillSyntheticAsync(rows, cols)` fills it natively; `setNumberColumn(col, Float64Array)` and `setTextColumn(col, Int32Array, string[])` copy from JS.
- Layout math (`GridGeometry`), the same on both platforms, with unit tests on Android.
- Perf overlay and monitor (`PerfMonitor`): fps, dropped frames, blank area, memory, time to first frame.
- Scripted benchmark (`BenchmarkDriver`): the same flings on every candidate and both platforms.

## Metrics

- **FPS / dropped frames.** From `CADisplayLink` (iOS) and `Choreographer` (Android). A callback that arrives later than one refresh interval counts the missed refreshes as dropped. This sees the UI thread only. Render-server (iOS) and RenderThread (Android) hitches don't show up here; check those with Instruments and `adb shell dumpsys gfxinfo <package> framestats` before trusting a close result.
- **Blank area.** At every display refresh: the visible area that should show a cell but has none drawn, divided by the viewport area covered by content. Reported as the mean over the run, the max, and the share of frames where more than 1% was blank. This is the same definition as `TableScrollBench.m` in workspace-sh/table-file-format (mean per-refresh share of the table's place with no rows drawn), except it's per cell rather than per row strip. For Android A and C it is 0 by construction: every visible cell is drawn synchronously in each frame, so a slow frame shows stale pixels, not empty ones, and shows up as dropped frames instead.
- **Memory.** iOS: `phys_footprint` (what Xcode and jetsam use). Android: RSS from `/proc/self/statm`, plus Java heap in the overlay.
- **Time to first frame.** From native view creation to the first display refresh where at least one body cell is drawn and nothing visible is blank. The example remounts the view for every run so this includes view creation.

## The benchmark script

`BenchmarkDriver` jumps to row 500,000 (to exercise large coordinates), waits 0.5 s, then runs 11 flings: 4 down and 2 up at 8,000 pt/s, 2 right and 1 left at 5,000 pt/s, 2 diagonal at 7,000 pt/s. Each fling decays like `UIScrollView`'s normal deceleration (0.998 per ms) until it's below 20 pt/s, with 150 ms between flings. Position is a function of time, not frames, so a candidate that drops frames still covers the same ground. On Android the velocities are in dp/s.

Real flings with a finger are also measured by the overlay at any time, but the scripted run is what goes in the table.

## Running it

Release builds only. Debug numbers mean nothing (the example's header says DEBUG BUILD when that's the case).

```sh
npm install
cd example
npm install

# iPhone / iPad (device must be plugged in or paired)
npx expo run:ios --device --configuration Release

# Android phone (USB debugging on)
npx expo run:android --device --variant release
```

The Android release variant of the example is signed with the debug keystore, so it installs without setup.

In the app:

1. Pick 1M and tap **Load**. The native fill time is shown under the buttons.
2. Tap **All × 3**. It remounts the grid for each candidate and runs the script 3 times each (about 2 minutes). Don't touch the screen.
3. Tap **Load path** to time copying 50 `Float64Array` columns and one dictionary-encoded text column (1M indices, 10,000 unique strings) from JS. The grid then shows the JS-generated data.
4. Tap **Share** and send the JSON. It has the device info, every run, the medians and the load-path numbers.

Repeat on: a recent iPhone, an old Android phone (2018–2019 mid-range), and an iPad if there is one. Keep the phone plugged in, at full brightness, with no low-power mode, and let it cool between devices.

For iOS A, also scroll by hand near row 1,000,000 (fling to the bottom) and look for jittering or misaligned cells. See "Large coordinates" below.

## Results

Fill in from the shared JSON (medians of 3 runs, 1M × 50).

| Device | Candidate | FPS (of Hz) | Dropped | Worst frame | Blank avg / max / % frames | Peak memory | TTFF |
|---|---|---|---|---|---|---|---|
| | iOS A | | | | | | |
| | iOS B | | | | | | |
| | Android A | | | | | | |
| | Android B | | | | | | |
| | Android C | | | | | | |

| Device | 50 × Float64Array (400 MB) | Native copy part | 1M dictionary-encoded strings | Native fill (for comparison) |
|---|---|---|---|---|
| | | | | |

## Findings so far (before device numbers)

These came out of checking the installed `expo-modules-core` 58.0.29 source and the baseline repo.

1. **1M × 50 doubles is 400 MB, not 40 MB.** On a 3–4 GB Android phone that's a real share of what the system lets one app keep. Float32 would halve it; storing only the columns a screen needs would be the real fix. Measure peak memory on the old phone before anything else.
2. **Android direct `ByteBuffer`s live on the Java heap.** `ByteBuffer.allocateDirect` is backed by a non-movable array on ART, so 400 MB would hit the per-app heap limit (typically 256–512 MB). The store allocates columns with `malloc` through a small JNI file (`android/src/main/cpp/native_buffers.cpp`, ~30 lines, no dependencies) and reads them through a direct `ByteBuffer`, so cell reads don't cross JNI.
3. **Expo doesn't copy typed arrays; it hands native code a view of JS memory.** On iOS `Float64Array.rawPointer` and on Android `toDirectBuffer()` point straight at the JS `ArrayBuffer`, valid only during a synchronous call on the JS thread. So `setNumberColumn` is a sync `Function` that `memcpy`s into native memory. Expo also supports native-owned `ArrayBuffer`s (iOS `ArrayBuffer(size:)`, Android `ArrayBuffer(ByteBuffer)`), which would let JS write straight into the store with no copy at all. Worth trying in Milestone 1 if the copy shows up in the numbers.
4. **The minimum iOS version is 16.4,** set by `ExpoModulesCore.podspec` in SDK 58. 16.0 isn't possible with Expo modules.
5. **The baseline numbers need checking.** In workspace-sh/table-file-format, the 23–38% "empty" figures for hard flicks in `docs/LARGE-TABLES.md` are from the **macOS** app (react-native-macos, M1 MacBook), and the doc counts about 37 views and 56 layers per row. The same doc's iOS run (iPhone 17 Pro Simulator, FlashList 2.3.3 and LegendList 3.6.0, 1M rows) reports 60 fps and no blank frames. I couldn't read issue #490 from here. If #490 is the macOS run, the launch line "0% blank vs 23–35% for JS lists" doesn't hold for iOS, and Milestone 2 has to measure the JS lists on the same phones with the same overlay before quoting anything.
6. **Large coordinates.** Row 1,000,000 is 32M points down. UIKit keeps frames as `Double`, but Core Animation renders in `Float`, which can't address single points past ~16.7M. iOS A puts cells at their content position, so it may jitter near the end. iOS B, Android A and Android C keep everything viewport-relative. Android B is relative by design. The benchmark starts at row 500,000 (16M points) for this reason.
7. **`create-expo-module` 58.0.3 with template 58.0.9 has no `tsconfig.json`,** so its `prepare` step fails and it never creates the example. I added a strict `tsconfig.json` and made the example with `create-expo-app` (blank-typescript, SDK 58), then applied the same app.json and package.json changes `create-expo-module` would have made.
8. **ProMotion.** The example sets `CADisableMinimumFrameDurationOnPhone` so iPhones can reach 120 Hz. Without it, every candidate tops out at 60.

## Build status

Built and run in debug on 1 Nov 2026 (Xcode 26.4.1, AGP 9.2.1, Kotlin 2.2.0, NDK 27.1):

- iOS, iPhone 17 Pro simulator (iOS 26.4): A and B draw the frozen header and first column. B scrolls both ways; A was seen scrolling horizontally, vertical scrolling on A is not confirmed yet. B first drew cells on black because an opaque layer's backing store hides `backgroundColor`; fixed by painting the background in `draw(in:)`.
- Android, API 35 emulator: A, B and C draw and scroll both ways. B first showed wrong numbers because `TextView.setText(char[], ...)` keeps the array and every cell shared one; each cell now has its own buffer.

Nothing has been measured on a physical device or in a release build yet.

Local setup notes: `pod install` needs `LANG=en_US.UTF-8`, and a machine with no emulator needs an AVD created first.

Spike-only dependencies, to drop once a candidate wins: `androidx.recyclerview` (B) and Compose foundation/ui plus the Compose compiler plugin (C).
