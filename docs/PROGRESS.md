# Development Progress

Legend: ✅ done and verified · 🟡 written, not yet built/run on Android · ⏳ planned

## Phase 1 — Foundation (current)

| Item | Status | Verification |
|---|---|---|
| Multi-module Gradle project, version catalog, wrapper | 🟡 | Not built: the authoring environment had no Android SDK and no access to Google Maven |
| `:core:common` (result types, logger, frame limiter) | ✅ | 8 JUnit tests pass (compiled with Kotlin 2.0.21) |
| `:core:model` (cards, game state, detection & equity types, validator) | ✅ | 34 JUnit tests pass |
| `:core:domain` (repositories, use cases, MIME policy) | ✅ | 8 JUnit tests pass |
| `SettingsDecoder` (`:core:data`) | ✅ | 3 JUnit tests pass |
| Room database v1 + DAOs + migration policy | 🟡 | Instrumented `DaoTest`, `MigrationTest` written |
| Repositories, file store, DataStore settings, Hilt DI | 🟡 | — |
| Dark theme, shared components, fa/en strings, RTL | 🟡 | Resource keys/placeholders checked by script for en/fa parity |
| Home dashboard | 🟡 | Compose UI test `HomeScreenTest` written |
| CameraX: preview, photo, video (no audio), lens switch, throttled analysis with FPS diagnostics, permission flow | 🟡 | — |
| Image import (Photo Picker) + gallery + detail + delete | 🟡 | — |
| Video import + list + Media3 playback + delete | 🟡 | — |
| History (media + games), privacy (usage, delete all), variant & detection settings | 🟡 | `SettingsViewModelTest` written |
| Navigation to all 10 screens; calculator & statistics show phase notices | 🟡 | `RoutesTest` written |

**Phase 1 exit criteria still open:** run `./gradlew assembleDebug testDebugUnitTest connectedDebugAndroidTest` on a machine with the Android SDK, fix anything that fails, commit `core/database/schemas/1.json`, and record results here.

## Next

| Phase | Scope |
|---|---|
| 2 | Frame extraction from video (MediaMetadataRetriever / MediaCodec), keyframe sampling, EXIF orientation, resize/rotate utilities, configurable processing rate for video |
| 3 | `:core:vision`: `CardDetector` interface, clearly labelled mock, LiteRT/ONNX runtime, YOLO post-processing (NMS), IoU tracker; `ml/` training pipeline |
| 4 | Game-state reconstruction, temporal agreement for confirmation, manual correction UI |
| 5 | Texas Hold'em evaluator (7-card best-5), exhaustive tests |
| 6 | Equity: exact enumeration + seeded Monte Carlo with Wilson intervals, pot odds, outs, EV; calculator screen |
| 7 | Camera → detector → state → engine integration; on-device benchmarks |
| 8 | Video timeline review, statistics, export |
| 9 | Omaha, Omaha Hi-Lo, Stud, Draw, Short Deck engines |
| 10 | Hardening, performance, release prep |
