# Known Limitations

## Build status
- Builds and all automated tests pass in GitHub Actions. Camera, import and playback have not yet been checked by hand on a real phone (emulator camera is not representative).

## Functional
- **No automatic card recognition yet:** cards are entered by tapping. A trained detection model is required (needs real photos of physical cards).
- **Advisor covers Texas Hold'em only.** Other variants show a notice.
- **Advice quality:** opponents are modelled as holding random cards. Real opponents who stay in or bet big usually hold stronger hands, so the advice is too optimistic in those spots. Position, bet sizing, bluffing and future betting rounds are ignored. It is a learning aid, not a solver.
- "Improving cards" counts cards that improve hero's own hand category, not true outs against an opponent's hand.
- Game sessions/hand histories are not recorded yet; statistics remain a placeholder.
- Videos imported with v0.2 still appear in History (delete only).

## Technical
- Imported media is copied in full into app storage; very large videos take time and space. There is no size limit or progress percentage yet.
- If a photo/video is written but the database insert fails, the file is deleted; if the app is killed between the camera write and the insert, an orphan file can remain (counted in storage usage, removed by "Delete all data").
- Confidence thresholds have untuned defaults (0.50 / 0.90). They must be calibrated against a real validation set.
- Performance targets (98% recognition, 5–10 FPS) are goals; nothing has been measured.
