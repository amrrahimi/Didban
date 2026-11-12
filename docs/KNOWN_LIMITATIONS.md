# Known Limitations

## Build status
- Builds and all automated tests pass in GitHub Actions. Camera, import and playback have not yet been checked by hand on a real phone (emulator camera is not representative).

## Functional (by design for phase 1)
- No card detection: no model exists yet. The camera samples frames at the configured rate and only measures throughput.
- No hand evaluation, equity, odds or statistics yet (phases 5–8). Calculator and Statistics screens show planned-feature notices only.
- Game sessions are never created yet (needs phase 4), so the Games tab of History is empty.
- No export of history yet (phase 8). Delete-all works.
- Only the default variant is stored; per-variant engines do not exist yet.

## Technical
- Keyframe extraction runs while the video screen is open; leaving the screen stops it (results so far are kept). Background extraction with WorkManager is planned with batch analysis.
- Exact-frame decoding (OPTION_CLOSEST) is accurate but slow on long high-resolution videos; extraction is capped at 600 sampled frames (the interval widens automatically for long videos).
- Keyframes are stored in the app cache; Android may delete them under storage pressure (they can be re-extracted).
- The keyframe fingerprint reacts to lighting changes and camera movement as well as card changes; it is a pre-filter, not card recognition.
- Imported media is copied in full into app storage; very large videos take time and space. There is no size limit or progress percentage yet.
- Videos imported before version 2 have no thumbnail (icon shown instead).
- If a photo/video is written but the database insert fails, the file is deleted; if the app is killed between the camera write and the insert, an orphan file can remain (counted in storage usage, removed by "Delete all data").
- Confidence thresholds have untuned defaults (0.50 / 0.90). They must be calibrated against a real validation set.
- Performance targets (98% recognition, 5–10 FPS) are goals; nothing has been measured.
