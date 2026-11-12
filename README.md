# Poker Vision AI (Android)

Offline poker analysis for **education, training and post-game review**: capture or import table photos and videos, recognise cards (planned), reconstruct game state and compute probabilities with deterministic poker math. Not intended for real-time assistance where it is prohibited.

**Status: Phase 1 (foundation).** See [docs/PROGRESS.md](docs/PROGRESS.md) and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

## What works in Phase 1 (code complete, awaiting first Android build)
- Home dashboard with live counts and navigation to all 10 screens
- Live camera (CameraX): preview, photo, video without audio, lens switch, analysis stream throttled to a configurable FPS with diagnostics
- Image and video import via the system Photo Picker, copied into private storage; gallery, detail, playback (Media3), delete
- Room database (versioned, schema export), DataStore settings, Hilt DI
- Game variant, model/detection and privacy/storage settings; delete-all
- Dark poker theme, English and Persian with RTL

## Docs
- [Architecture](docs/ARCHITECTURE.md) · [Setup](docs/SETUP.md) · [Testing](docs/TESTING_STRATEGY.md) · [ML pipeline](docs/ML_PIPELINE.md)

## Quick start
```bash
./gradlew assembleDebug && ./gradlew installDebug
./gradlew test
```
