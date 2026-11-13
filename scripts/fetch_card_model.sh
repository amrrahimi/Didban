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
# Pinned checksum of the model this app was built and tested against. If upstream changes the file,
# the build fails here instead of silently shipping a different model.
EXPECTED_SHA256="8b767cdfed2c8e954a9134013ac3d2f2c53be048768d559675be01277a8a8fd1"
ACTUAL_SHA256="$(sha256sum "$DEST/card_detector.onnx" | cut -d' ' -f1)"
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "Card model checksum mismatch: expected $EXPECTED_SHA256, got $ACTUAL_SHA256" >&2
  exit 1
fi
ls -la "$DEST"
