"""Builds a YOLO dataset from the app's "Learn my cards" export.

Input zip layout (from TrainingCaptureStore): cards/<CODE>/<nnn>.jpg, cards/background/<nnn>.jpg,
manifest.json. CODE is the model label, e.g. "AS", "10H".

Output (YOLO format): <out>/images/{train,val}/*.jpg, <out>/labels/{train,val}/*.txt, <out>/data.yaml
and <out>/test/<CODE>_<n>.jpg (held-out real photos, never used for training or for composites).

Split (no leakage between train and test): for every card the last TEST_SHOTS photos (by shot index)
are held out as the test set. Synthetic table scenes are built only from training photos.
"""
from __future__ import annotations

import argparse
import json
import random
import shutil
import zipfile
from pathlib import Path

import cv2
import numpy as np

from card_geometry import CANON_H, CANON_W, INDEX_BR, INDEX_TL, card_homography, extract_card, find_card_quad, index_boxes, region_box

# Same class order as the app (CardLabels.DEFAULT_ORDER) and the original model.
NAMES = [r + s for r in ["10", "2", "3", "4", "5", "6", "7", "8", "9", "A", "J", "K", "Q"] for s in "CDHS"]
CLASS = {n: i for i, n in enumerate(NAMES)}
TEST_SHOTS = 2
IMG = 640


def yolo_line(cls: int, box, w: int, h: int) -> str:
    x1, y1, x2, y2 = box
    return f"{cls} {(x1 + x2) / 2 / w:.6f} {(y1 + y2) / 2 / h:.6f} {(x2 - x1) / w:.6f} {(y2 - y1) / h:.6f}"


def letterbox_square(img: np.ndarray, size: int = IMG):
    """Resize keeping aspect into a size x size canvas; returns image, scale and offsets."""
    h, w = img.shape[:2]
    s = size / max(h, w)
    nw, nh = int(round(w * s)), int(round(h * s))
    canvas = np.full((size, size, 3), 114, np.uint8)
    ox, oy = (size - nw) // 2, (size - nh) // 2
    canvas[oy:oy + nh, ox:ox + nw] = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_AREA)
    return canvas, s, ox, oy


def load_zip(path: Path, work: Path):
    if work.exists():
        shutil.rmtree(work)
    with zipfile.ZipFile(path) as z:
        z.extractall(work)
    cards, backgrounds = {}, []
    for d in sorted((work / "cards").iterdir()):
        files = sorted(d.glob("*.jpg"))
        if d.name == "background":
            backgrounds = files
        elif d.name in CLASS:
            cards[d.name] = files
    return cards, backgrounds


def random_felt(rng: random.Random) -> np.ndarray:
    base = np.array([rng.randint(10, 80), rng.randint(40, 140), rng.randint(10, 90)], np.uint8)
    img = np.empty((IMG, IMG, 3), np.uint8)
    img[:] = base
    noise = np.random.default_rng(rng.randint(0, 2**31)).integers(-12, 12, img.shape)
    return np.clip(img.astype(int) + noise, 0, 255).astype(np.uint8)


def background_tile(backgrounds, rng: random.Random) -> np.ndarray:
    if backgrounds and rng.random() < 0.7:
        bg = cv2.imread(str(rng.choice(backgrounds)))
        if bg is not None:
            return cv2.resize(bg, (IMG, IMG), interpolation=cv2.INTER_AREA)
    return random_felt(rng)


def composite(textures, backgrounds, rng: random.Random):
    """One synthetic table scene: 1-6 cards with random pose; labels only for visible index corners."""
    canvas = background_tile(backgrounds, rng)
    owner = np.full((IMG, IMG), -1, np.int32)  # which pasted card is on top of each pixel
    placed = []
    for k in range(rng.randint(1, 6)):
        code, tex = rng.choice(textures)
        scale = rng.uniform(0.35, 0.95) * IMG / CANON_H
        angle = np.deg2rad(rng.uniform(-180, 180))
        cx, cy = rng.uniform(0.15, 0.85) * IMG, rng.uniform(0.15, 0.85) * IMG
        pts = np.array([[-CANON_W / 2, -CANON_H / 2], [CANON_W / 2, -CANON_H / 2],
                        [CANON_W / 2, CANON_H / 2], [-CANON_W / 2, CANON_H / 2]]) * scale
        rot = np.array([[np.cos(angle), -np.sin(angle)], [np.sin(angle), np.cos(angle)]])
        dst = pts @ rot.T + [cx, cy]
        dst += np.array([[rng.uniform(-1, 1), rng.uniform(-1, 1)] for _ in range(4)]) * 0.06 * scale * CANON_H
        dst = dst.astype(np.float32)
        H = cv2.getPerspectiveTransform(np.float32([[0, 0], [CANON_W, 0], [CANON_W, CANON_H], [0, CANON_H]]), dst)
        warped = cv2.warpPerspective(tex, H, (IMG, IMG), flags=cv2.INTER_LINEAR)
        mask = cv2.warpPerspective(np.full((CANON_H, CANON_W), 255, np.uint8), H, (IMG, IMG)) > 127
        canvas[mask] = warped[mask]
        owner[mask] = k
        placed.append((code, H))
    labels = []
    for k, (code, H) in enumerate(placed):
        for region in (INDEX_TL, INDEX_BR):
            box = region_box(H, region, IMG, IMG)
            if box is None:
                continue
            full = region_box(H, region, IMG, IMG, clip=False)
            full_area = max((full[2] - full[0]) * (full[3] - full[1]), 1.0)
            x1, y1, x2, y2 = (int(v) for v in box)
            visible = (owner[y1:y2 + 1, x1:x2 + 1] == k).sum()
            if visible < 0.75 * full_area:
                continue  # index covered by another card or partly off the image: not labelled
            labels.append(yolo_line(CLASS[code], box, IMG, IMG))
    return photometric(canvas, rng), labels


def photometric(img: np.ndarray, rng: random.Random) -> np.ndarray:
    """Lighting, blur and compression variety (Ultralytics adds HSV/scale on top during training)."""
    out = img.astype(np.float32) * rng.uniform(0.6, 1.3) + rng.uniform(-30, 30)
    out = np.clip(out, 0, 255).astype(np.uint8)
    if rng.random() < 0.4:
        k = rng.choice([3, 5])
        out = cv2.GaussianBlur(out, (k, k), 0)
    if rng.random() < 0.5:
        ok, enc = cv2.imencode(".jpg", out, [cv2.IMWRITE_JPEG_QUALITY, rng.randint(45, 90)])
        out = cv2.imdecode(enc, cv2.IMREAD_COLOR)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", required=True)
    ap.add_argument("--out", default="ml/build/dataset")
    ap.add_argument("--composites", type=int, default=1600)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True)
        (out / "labels" / split).mkdir(parents=True)
    (out / "test").mkdir(parents=True)

    cards, backgrounds = load_zip(Path(args.zip), out.parent / "unzipped")
    report = {"cards_found": len(cards), "backgrounds": len(backgrounds), "photos": 0, "labelled": 0,
              "no_card_outline": [], "test_photos": 0, "missing_cards": sorted(set(NAMES) - set(cards))}
    textures = []
    n = 0
    for code, files in cards.items():
        train_files = files[:-TEST_SHOTS] if len(files) > TEST_SHOTS else files
        for f in files[len(train_files):]:
            shutil.copy(f, out / "test" / f"{code}_{f.stem}.jpg")
            report["test_photos"] += 1
        for f in train_files:
            report["photos"] += 1
            img = cv2.imread(str(f))
            quad = find_card_quad(img) if img is not None else None
            if quad is None:
                report["no_card_outline"].append(f"{code}/{f.name}")
                continue
            textures.append((code, extract_card(img, quad)))
            sq, s, ox, oy = letterbox_square(img)
            boxes = [(x1 * s + ox, y1 * s + oy, x2 * s + ox, y2 * s + oy) for x1, y1, x2, y2 in index_boxes(quad, *img.shape[1::-1])]
            split = "val" if rng.random() < 0.1 else "train"
            cv2.imwrite(str(out / "images" / split / f"real_{n:05d}.jpg"), sq)
            (out / "labels" / split / f"real_{n:05d}.txt").write_text("\n".join(yolo_line(CLASS[code], b, IMG, IMG) for b in boxes))
            report["labelled"] += 1
            n += 1
    # Empty-table photos are negatives (no labels) and composite backgrounds.
    for i, f in enumerate(backgrounds):
        img = cv2.imread(str(f))
        if img is not None:
            cv2.imwrite(str(out / "images" / "train" / f"bg_{i:04d}.jpg"), letterbox_square(img)[0])
            (out / "labels" / "train" / f"bg_{i:04d}.txt").write_text("")
    if not textures:
        raise SystemExit("No card outline found in any training photo")
    for i in range(args.composites):
        img, labels = composite(textures, backgrounds, rng)
        split = "val" if i % 10 == 0 else "train"
        cv2.imwrite(str(out / "images" / split / f"syn_{i:05d}.jpg"), img)
        (out / "labels" / split / f"syn_{i:05d}.txt").write_text("\n".join(labels))
    report["composites"] = args.composites
    (out / "data.yaml").write_text(
        f"path: {out.resolve()}\ntrain: images/train\nval: images/val\nnames:\n" + "".join(f"  {i}: {n}\n" for i, n in enumerate(NAMES)))
    (out / "prepare_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({k: (v if not isinstance(v, list) else f"{len(v)} items") for k, v in report.items()}, indent=2))


if __name__ == "__main__":
    main()
