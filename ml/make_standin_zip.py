"""Builds a stand-in "Learn my cards" export from public card photos (Hugging Face dataset
Mariofm02/cards_images) so the training pipeline can be tested before the user's photos arrive.
Images are tight card crops; they are resized to card proportions so the card fills the image.
Not used to train the shipped model.
"""
import argparse
import io
import json
import random
import time
import urllib.request
import zipfile

import cv2
import numpy as np

DATASET = "Mariofm02/cards_images"
RANKS = {"ace": "A", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6", "seven": "7", "eight": "8",
         "nine": "9", "ten": "10", "jack": "J", "queen": "Q", "king": "K"}
SUITS = {"clubs": "C", "diamonds": "D", "hearts": "H", "spades": "S"}


def get(url, attempts=5):
    for i in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                return r.read()
        except Exception as e:
            last = e
            time.sleep(3 * (i + 1))
    raise last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="ml/build/standin.zip")
    ap.add_argument("--per-card", type=int, default=6)
    args = ap.parse_args()
    base = f"https://datasets-server.huggingface.co/rows?dataset={DATASET}&config=default&split=train"
    first = json.loads(get(base + "&offset=0&length=1"))
    names = next(f["type"]["names"] for f in first["features"] if f["name"] == "label")
    total = first["num_rows_total"]
    counts = {}
    with zipfile.ZipFile(args.out, "w") as z:
        z.writestr("manifest.json", json.dumps({"format": "pokervision-card-capture-v1", "source": DATASET}))
        offsets = list(range(0, total, 100))
        random.Random(3).shuffle(offsets)
        for off in offsets:
            if len(counts) == 52 and min(counts.values()) >= args.per_card:
                break
            try:
                rows = json.loads(get(base + f"&offset={off}&length=100"))["rows"]
            except Exception as e:
                print("skip batch", off, e)
                continue
            for r in rows:
                parts = names[r["row"]["label"]].lower().split(" of ")
                if len(parts) != 2 or parts[0] not in RANKS or parts[1] not in SUITS:
                    continue
                code = RANKS[parts[0]] + SUITS[parts[1]]
                if counts.get(code, 0) >= args.per_card:
                    continue
                try:
                    img = cv2.imdecode(np.frombuffer(get(r["row"]["image"]["src"]), np.uint8), cv2.IMREAD_COLOR)
                except Exception:
                    continue
                img = cv2.resize(img, (252, 352))
                z.writestr(f"cards/{code}/{counts.get(code, 0):03d}.jpg", cv2.imencode(".jpg", img)[1].tobytes())
                counts[code] = counts.get(code, 0) + 1
        for i in range(8):
            bg = np.full((640, 640, 3), (random.randint(20, 60), random.randint(60, 120), random.randint(20, 60)), np.uint8)
            bg = np.clip(bg.astype(int) + np.random.default_rng(i).integers(-15, 15, bg.shape), 0, 255).astype(np.uint8)
            z.writestr(f"cards/background/{i:03d}.jpg", cv2.imencode(".jpg", bg)[1].tobytes())
    print("cards:", len(counts), "photos:", sum(counts.values()))


if __name__ == "__main__":
    main()
