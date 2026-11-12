# Machine Learning Pipeline (plan for phase 3)

Nothing here is trained yet. No accuracy figures exist.

## Classes
52 card classes (`As` … `2c`, detected on the card's corner index), plus `card_back`, `chip`, `chip_stack`, `dealer_button`, `table`. Detecting corner indices rather than whole cards keeps recognition working when cards overlap in a fan.

## Dataset
- Sources: own photos/videos of physical decks across several deck designs; synthetic composites (card crops pasted on varied table backgrounds with random perspective, blur, glare).
- **Leakage prevention:** split by *recording session and deck design*, never by frame. Frames from one video all go to the same split. Synthetic images built from a card photo stay in that photo's split. A held-out deck design appears only in test.
- Splits: train 70 / val 15 / test 15 (by session).
- Evaluation slices: lighting (bright, dim, mixed/glare), angle (top-down, 30°, 60°), occlusion (none, partial, fanned), background (felt colors, wood, cloth), distance.

## Annotation guidelines
- One box per visible corner index (rank + suit), tight to the glyphs.
- Label only if a human can read it with certainty; otherwise mark `ignore` so it isn't counted as a false positive.
- Card backs labelled as `card_back`; never guess the face.

## Training
- Ultralytics YOLO (nano/small) at 640 px; augmentations: HSV, perspective, motion blur, cutout. Horizontal flip **disabled** (it mirrors glyphs).
- Track runs with config + dataset hash; keep the exact test-set version.

## Deployment
- Export to TFLite (LiteRT) with INT8 post-training quantization using a representative set from *train* only; compare against FP16 on the test set and keep INT8 only if the accuracy drop is acceptable.
- On-device benchmark app screen: latency p50/p95, FPS, memory, per device model. Results go into `docs/BENCHMARKS.md` from real devices only.
- Runtime: LiteRT with GPU delegate where available, CPU XNNPACK fallback.
