#!/usr/bin/env bash
# Downloads the playing-card detector into core/vision/src/main/assets/models/.
# Model: sroot/lgd-cards-gen3 (YOLO11s, 52 classes, detects corner pips). License: AGPL-3.0 (weights).
# The weights are not committed to git (size + separate license); CI fetches them before building.
set -euo pipefail
REPO="sroot/lgd-cards-gen3"
REV="${CARD_MODEL_REVISION:-main}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/core/vision/src/main/assets/models"
mkdir -p "$DEST"
for f in model.onnx model.classes.json; do
  curl -fsSL --retry 3 "https://huggingface.co/${REPO}/resolve/${REV}/${f}" -o "$DEST/card_detector_${f#model}"
done
# Normalise names: card_detector_.onnx -> card_detector.onnx, card_detector_.classes.json -> card_detector.classes.json
mv "$DEST/card_detector_.onnx" "$DEST/card_detector.onnx"
mv "$DEST/card_detector_.classes.json" "$DEST/card_detector.classes.json"
sha256sum "$DEST"/card_detector.* 
ls -la "$DEST"
