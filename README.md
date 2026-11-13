# Poker Vision AI (Android)

Offline poker **live advisor for learning and friendly games where every player knows it is used**: enter (and, once the detection model exists, photograph) the cards and get odds plus a suggested move in under a second. Card rooms and online sites prohibit this kind of assistance; do not use it there.

**Status: v0.3 — Live Advisor for Texas Hold'em (manual card entry). Automatic card recognition is next.** See [docs/PROGRESS.md](docs/PROGRESS.md) and [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

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
