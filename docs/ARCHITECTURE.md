# Poker Vision AI — Technical Architecture

## 1. Principles

- **On-device first.** No `INTERNET` permission in the app. Media is copied into private storage; nothing leaves the device.
- **Evidence vs. facts.** Vision output (`DetectionResult`) is *evidence*. Game state (`GameState`) holds *facts* (`CardSlot.Confirmed`) separately from *uncertain candidates* (`CardSlot.Uncertain`). Calculations only ever consume confirmed cards.
- **Honest placeholders.** Screens for later phases are reachable but display a "Planned for phase N" notice — never mock numbers. A future mock detector is flagged by `ModelInfo.isMock` and must never feed confirmed state.
- **Pure core.** Card model, validation, probability math and use cases are plain Kotlin/JVM: fast to test, no Android needed.

## 2. Layers (Clean Architecture + MVVM)

```
 UI (Compose screens)  ──►  ViewModel (StateFlow)  ──►  Use case (domain)  ──►  Repository interface (domain)
                                                                                     ▲
                                                           Repository impl (data) ───┘──► Room / DataStore / files / CameraX
```

Dependencies always point inward: `feature → domain → model/common`. `data` implements `domain` interfaces. Nothing in `domain`/`model` imports Android.

## 3. Modules

| Module | Type | Responsibility |
|---|---|---|
| `:core:common` | Kotlin/JVM | `AppResult`/`AppError`, `AppLogger`, `FrameRateLimiter`, `ThroughputMeter` |
| `:core:model` | Kotlin/JVM | Cards, deck types, confidence, `GameState`, `CardSlot`, detection & equity result types, `GameStateValidator` |
| `:core:domain` | Kotlin/JVM | Repository interfaces, use cases, `MediaTypePolicy`, frame sampling / keyframe selection / resize math, `ExtractKeyframesUseCase` |
| `:core:database` | Android lib | Room DB v1: `media_assets`, `games`, `hand_histories`; DAOs; migration policy |
| `:core:data` | Android lib | Repository implementations, `MediaFileStore`, DataStore settings, Hilt bindings, `AndroidLogger` |
| `:core:media` | Android lib | Video frame reader (MediaMetadataRetriever), `BitmapOps` (EXIF, resize, rotate, luma fingerprint, atomic JPEG), keyframe cache, video thumbnails |
| `:core:ui` | Android lib | Dark poker theme, shared components (confidence indicator, planned-feature notice, card view), formatting, shared strings |
| `:feature:home` | Android lib | Dashboard (screen 1) |
| `:feature:camera` | Android lib | CameraX preview / photo / video / throttled analysis stream (screen 2) |
| `:feature:imageanalysis` | Android lib | Image import, gallery, detail (screen 3) |
| `:feature:videoanalysis` | Android lib | Video import, list, Media3 playback (screen 4) |
| `:feature:history` | Android lib | Media and game history (screen 6) |
| `:feature:settings` | Android lib | Variant selection (8), model & detection (9), privacy & storage (10) |
| `:app` | Application | Hilt app, single activity, navigation, language switching, placeholder screens 5 & 7 |

Planned modules (not created yet, to avoid empty shells): `:core:vision` (detector interface, YOLO/LiteRT inference, tracker — phase 3), `:core:gamestate` (reconstruction — phase 4), `:core:poker` (engines per variant — phases 5/9), `:core:analytics` (equity, Monte Carlo — phase 6), `:feature:calculator`, `:feature:statistics`, `ml/` (Python training pipeline).

### Module dependency graph

```
app ─► feature:* ─► core:ui ─► core:model, core:common
  │        └──────► core:domain ─► core:model, core:common
  ├─► core:data ─► core:domain, core:database
  └─► core:database
```

## 4. Core data models (`:core:model`)

| Model | Notes |
|---|---|
| `PlayingCard(rank, suit)` | `index` 0..51 for bitmasks; `code` "As"; `CardCodec` for storage |
| `DeckType` | `STANDARD_52`, `SHORT_DECK_36` |
| `Confidence`, `ConfidenceThresholds`, `Observed<T>` | Every observed field carries confidence + `ObservationSource` (VISION / MANUAL / INFERRED) |
| `CardSlot` | `Unknown` / `Confirmed` / `Uncertain(candidates)` |
| `Player`, `PokerTable`, `BettingAction`, `ChipAmount` | Chip amounts are non-negative `Long` units |
| `PokerVariant` | Structural rules (cards per player, board size, player limits, streets, deck) |
| `GameState` | One hand snapshot; validated by `GameStateValidator` |
| `PokerGame`, `HandHistory` | Persisted session and hand records |
| `DetectionResult`, `Detection`, `BoundingBox`, `ModelInfo` | Raw inference output, normalized coordinates, IoU helper |
| `EquityResult`, `PlayerEquity`, `SimulationResult`, `ConfidenceInterval` | `isEstimate` for Monte Carlo; Wilson score interval |
| `MediaAsset`, `AppSettings` | Media records and user settings |

## 5. Key decisions

- **CameraX use-case combination:** Preview + ImageAnalysis + (ImageCapture *or* VideoCapture). Three use cases is supported on every hardware level including LEGACY; four is not guaranteed.
- **Analysis throttling:** `FrameRateLimiter` with 5% early tolerance so 30 FPS → 5 FPS yields 5 FPS, not 4.3.
- **Enums stored by name**, never ordinal; unknown names map to `null` and rows are skipped rather than crashing.
- **No destructive migration fallback.** Schemas exported to `core/database/schemas`.
- **Per-app language** via `AppCompatDelegate.setApplicationLocales` (hence `AppCompatActivity`), RTL via `android:supportsRtl` and `AutoMirrored` icons.
- **Backups disabled** (`allowBackup=false`, extraction rules exclude everything) to keep private game data on-device.
- **Video without audio:** no `RECORD_AUDIO` permission.
