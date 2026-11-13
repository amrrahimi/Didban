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

## Phase 2 — Camera and image input ✅

Video analysis (import, playback, keyframe extraction) was built and then **removed at the user's request (v0.3)**: the goal is live analysis from the camera, not recorded video. Kept from that work: EXIF-aware image sizes, `BitmapOps` (resize, rotate, luma fingerprint, atomic JPEG; `BitmapOpsTest` on emulator) and Room v2 (`MIGRATION_1_2`, tested).

## Direction change (v0.3): live advisor

The product goal is a **live advisor for learning and friendly games where all players know it is used**: the camera recognises cards and the app immediately suggests fold / check / call / bet / raise. Decision time in live poker is typically 10–30 s (TDA clock: 25 s + 5 s), so the target is a full answer in under ~1 s.

| Item | Status | Verification |
|---|---|---|
| `core:poker` Hold'em evaluator (5–7 cards) | ✅ | all 2,598,960 five-card hands match known category counts; 20,000 random 7-card hands match best-of-21 |
| Equity: exact enumeration (heads-up turn/river) and seeded Monte Carlo with 95% CI; dead cards | ✅ | matches published values (AA 85.2%, 72o 34.6%, AA vs 5 ≈49%); exact vs simulated agree |
| Pot odds, call EV, explainable basic advisor | ✅ | `EquityAndAdviceTest` |
| Speed | ✅ | desktop JVM: 9 opponents 14 ms; phone expected well under 0.5 s (to be measured on device) |
| Live Advisor screen (card picker, opponents, pot, to call, instant advice) | 🟡 | `AdvisorInputReducerTest`; manual phone check pending |

## v0.4 — Live Table (automatic card reading)

| Item | Status | Verification |
|---|---|---|
| Card model: sroot/lgd-cards-gen3 (YOLO11s, 52 classes, corner pips), checksum-pinned, fetched in CI | ✅ | ONNX inspected in CI: input `images` [1,3,640,640], output [1,56,8400], no NMS |
| ONNX Runtime on device (XNNPACK) | ✅ | `OnnxCardDetectorTest`: model loads on emulator; blank frame → no cards |
| Kotlin pipeline (letterbox, decode, NMS, pip clustering) | ✅ | Same results as an independent Python reference on 16 real card photos (identical hits, scores within ±0.01) |
| Multi-frame confirmation, hero/board assembly, reject, manual add | ✅ | `VisionPipelineTest` |
| Chip-free live advice (largest callable bet as % of pot) | ✅ | `LiveAdviceTest`, `LiveAnalysisTest` |
| Live Table screen | 🟡 | needs testing on a real phone at a real table |

**Measured so far (honest):** on 16 small (224×224) public card photos the model found 2 cards, both correct; one wrong guess (score 0.41) stayed below the confirmation threshold. These photos are far smaller than live camera frames, so real-table accuracy is still unknown. The model's publisher reports ~85% recall on real table video. Inference: ~350–450 ms per frame on the CI emulator.

## Next

| Step | Scope |
|---|---|
| A | **Card detection model** (needs real photos of the user's cards — see ML_PIPELINE.md); camera fills the advisor automatically; temporal confirmation so uncertain detections are never used |
| B | Omaha, Omaha Hi-Lo, Short Deck, Stud, Draw in the advisor (Stud uses seen up-cards as dead cards) |
| C | Opponent-range awareness (tighter ranges when opponents bet big) to improve advice quality |
| 3 | `:core:vision`: `CardDetector` interface, clearly labelled mock, LiteRT/ONNX runtime, YOLO post-processing (NMS), IoU tracker; `ml/` training pipeline |
| 4 | Game-state reconstruction, temporal agreement for confirmation, manual correction UI |
| 5 | Texas Hold'em evaluator (7-card best-5), exhaustive tests |
| 6 | Equity: exact enumeration + seeded Monte Carlo with Wilson intervals, pot odds, outs, EV; calculator screen |
| 7 | Camera → detector → state → engine integration; on-device benchmarks |
| 8 | Video timeline review, statistics, export |
| 9 | Omaha, Omaha Hi-Lo, Stud, Draw, Short Deck engines |
| 10 | Hardening, performance, release prep |
