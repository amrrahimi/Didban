"""Compares card models on held-out photos (prepare_dataset.py test/ folder).

Same preprocessing as the app: letterbox to 640 with gray padding, RGB, 0..1. A photo counts as
correct when the highest-scoring card (score >= THRESHOLD) is the card that was photographed.
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

from prepare_dataset import NAMES

THRESHOLD = 0.3
GROUPS = {"A": ["A"], "2-10": [str(i) for i in range(2, 11)], "J-Q-K": ["J", "Q", "K"]}


def letterbox(img, size=640):
    h, w = img.shape[:2]
    s = min(size / w, size / h)
    nw, nh = round(w * s), round(h * s)
    canvas = np.full((size, size, 3), 114, np.uint8)
    canvas[(size - nh) // 2:(size - nh) // 2 + nh, (size - nw) // 2:(size - nw) // 2 + nw] = cv2.resize(img, (nw, nh))
    return canvas


def predict(sess, img):
    x = cv2.cvtColor(letterbox(img), cv2.COLOR_BGR2RGB).astype(np.float32).transpose(2, 0, 1)[None] / 255.0
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]  # (4 + nc, anchors)
    scores = out[4:].max(axis=1)  # best score per class over all anchors
    cls = int(scores.argmax())
    return (NAMES[cls], float(scores[cls])) if scores[cls] >= THRESHOLD else (None, float(scores[cls]))


def evaluate(model_path, photos):
    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    per_group = defaultdict(lambda: [0, 0])
    correct = none = 0
    confusions = defaultdict(int)
    for p in photos:
        truth = p.stem.split("_")[0]
        pred, _ = predict(sess, cv2.imread(str(p)))
        rank = truth[:-1]
        group = next(g for g, ranks in GROUPS.items() if rank in ranks)
        per_group[group][1] += 1
        if pred == truth:
            correct += 1
            per_group[group][0] += 1
        elif pred is None:
            none += 1
        else:
            confusions[f"{truth}->{pred}"] += 1
    n = max(len(photos), 1)
    return {
        "photos": len(photos),
        "accuracy": correct / n,
        "no_detection": none / n,
        "by_group": {g: (c / t if t else None, t) for g, (c, t) in per_group.items()},
        "top_confusions": sorted(confusions.items(), key=lambda kv: -kv[1])[:8],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", default="ml/build/dataset/test")
    ap.add_argument("--candidate", default="ml/build/model/card_detector.onnx")
    ap.add_argument("--baseline", default="core/vision/src/main/assets/models/card_detector.onnx")
    ap.add_argument("--out", default="ml/build/model/report.md")
    args = ap.parse_args()
    photos = sorted(Path(args.test).glob("*.jpg"))
    results = {"candidate (trained on your deck)": evaluate(args.candidate, photos)}
    if Path(args.baseline).exists():
        results["current app model (lgd-cards-gen3)"] = evaluate(args.baseline, photos)
    lines = [f"# Card model evaluation\n", f"Held-out photos: {len(photos)} (never used in training).\n",
             "| Model | Correct | No detection | A | 2-10 | J-Q-K |", "|---|---|---|---|---|---|"]
    for name, r in results.items():
        g = r["by_group"]
        fmt = lambda k: f"{g[k][0]:.0%} of {g[k][1]}" if k in g and g[k][0] is not None else "-"
        lines.append(f"| {name} | {r['accuracy']:.1%} | {r['no_detection']:.1%} | {fmt('A')} | {fmt('2-10')} | {fmt('J-Q-K')} |")
    for name, r in results.items():
        if r["top_confusions"]:
            lines.append(f"\n{name} — most frequent mistakes: " + ", ".join(f"{k} ×{v}" for k, v in r["top_confusions"]))
    lines.append("\nCaveat: test photos come from the same capture session as the training photos (same light and table), "
                 "so real games may score lower.")
    report = "\n".join(lines)
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(report)
    Path(args.out).with_suffix(".json").write_text(json.dumps(results, indent=2))
    print(report)


if __name__ == "__main__":
    main()
