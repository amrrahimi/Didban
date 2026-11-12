# Development Progress

Legend: ✅ built and tested in CI · 🟡 built in CI, needs manual check on a real phone · ⏳ planned

CI: GitHub Actions on branch `poker-vision` runs all unit tests, builds the APK, runs instrumented tests on an API 30 emulator, and publishes the APK to the `poker-vision-latest` pre-release.

## Phase 1 — Foundation (current)

| Item | Status | Verification |
|---|---|---|
| Multi-module Gradle project, version catalog, wrapper | ✅ | `assembleDebug` succeeds in CI |
| `:core:common` (result types, logger, frame limiter) | ✅ | 8 JUnit tests pass (compiled with Kotlin 2.0.21) |
| `:core:model` (cards, game state, detection & equity types, validator) | ✅ | 34 JUnit tests pass |
| `:core:domain` (repositories, use cases, MIME policy) | ✅ | 8 JUnit tests pass |
| `SettingsDecoder` (`:core:data`) | ✅ | 3 JUnit tests pass |
| Room database v1 + DAOs + migration policy | ✅ | `DaoTest` (4) + `MigrationTest` (1) pass on emulator; schema v1 committed |
| Repositories, file store, DataStore settings, Hilt DI | 🟡 | — |
| Dark theme, shared components, fa/en strings, RTL | 🟡 | Resource keys/placeholders checked by script for en/fa parity |
| Home dashboard | ✅ | `HomeScreenTest` passes on emulator |
| CameraX: preview, photo, video (no audio), lens switch, throttled analysis with FPS diagnostics, permission flow | 🟡 | — |
| Image import (Photo Picker) + gallery + detail + delete | 🟡 | — |
| Video import + list + Media3 playback + delete | 🟡 | — |
| History (media + games), privacy (usage, delete all), variant & detection settings | 🟡 | `SettingsViewModelTest` passes |
| Navigation to all 10 screens; calculator & statistics show phase notices | 🟡 | `RoutesTest` passes |

**Phase 1 exit criterion still open:** manual check on a real phone (camera capture/recording, import, playback, Persian RTL, delete-all).

## Phase 2 — Camera, image and video input ✅ (CI green)

| Item | Status | Verification |
|---|---|---|
| Frame sampling plan (configurable interval, cap of 600 samples) | ✅ | `FrameSamplingTest` |
| Keyframe selection (32×32 luma fingerprint, min/max gap, 3 sensitivities) | ✅ | `FrameSamplingTest`, `ExtractKeyframesUseCaseTest` (progress, cancel, undecodable frames) |
| Video frame decoding (MediaMetadataRetriever, OPTION_CLOSEST, scaled on API 27+) | 🟡 | needs a real-phone check with a recorded game |
| Image utilities: EXIF orientation, subsampled decode, resize, rotate, luma, atomic JPEG | ✅ | `BitmapOpsTest` on emulator |
| Keyframe cache (locale-safe filenames, cleared on delete / delete-all) | ✅ | `BitmapOpsTest` (Persian locale), `UseCasesTest` |
| Room v2: video thumbnails, `MIGRATION_1_2` | ✅ | `MigrationTest` 1→2 preserves rows; schema 2 committed |
| UI: keyframe filmstrip with seek, thumbnails in lists, video settings | 🟡 | `SettingsViewModelTest`; manual check pending |

## Next

| Phase | Scope |
|---|---|
| 3 | `:core:vision`: `CardDetector` interface, clearly labelled mock, LiteRT/ONNX runtime, YOLO post-processing (NMS), IoU tracker; `ml/` training pipeline |
| 4 | Game-state reconstruction, temporal agreement for confirmation, manual correction UI |
| 5 | Texas Hold'em evaluator (7-card best-5), exhaustive tests |
| 6 | Equity: exact enumeration + seeded Monte Carlo with Wilson intervals, pot odds, outs, EV; calculator screen |
| 7 | Camera → detector → state → engine integration; on-device benchmarks |
| 8 | Video timeline review, statistics, export |
| 9 | Omaha, Omaha Hi-Lo, Stud, Draw, Short Deck engines |
| 10 | Hardening, performance, release prep |
