"""Reference check: runs the card model on the sample photos with a plain numpy/onnxruntime
implementation of Ultralytics preprocessing, to compare with the app's Kotlin pipeline."""
import glob, os, sys
import numpy as np
import onnxruntime as ort
from PIL import Image

MODEL = "core/vision/src/main/assets/models/card_detector.onnx"
SAMPLES = "core/vision/src/androidTest/assets/samples"
NAMES = [r + s for r in ["10", "2", "3", "4", "5", "6", "7", "8", "9", "A", "J", "K", "Q"] for s in "CDHS"]

def letterbox(img, size=640):
    w, h = img.size
    scale = min(size / w, size / h)
    nw, nh = round(w * scale), round(h * scale)
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(img.resize((nw, nh), Image.BILINEAR), ((size - nw) // 2, (size - nh) // 2))
    return canvas

sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
files = sorted(glob.glob(os.path.join(SAMPLES, "*.jpg")))
correct = 0
for f in files:
    expected = os.path.basename(f).split("_")[0]
    img = Image.open(f).convert("RGB")
    x = np.asarray(letterbox(img), dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
    out = sess.run(None, {sess.get_inputs()[0].name: x})[0][0]  # (56, anchors)
    scores = out[4:]
    cls = scores.argmax(0)
    best = scores.max(0)
    keep = best > 0.25
    found = {}
    for c, s in zip(cls[keep], best[keep]):
        found[NAMES[c]] = max(found.get(NAMES[c], 0), float(s))
    top = sorted(found.items(), key=lambda kv: -kv[1])[:3]
    ok = bool(top) and top[0][0] == expected
    correct += ok
    print(f"REF {os.path.basename(f)} size={img.size} expected={expected} top={[(k, round(v, 2)) for k, v in top]} {'OK' if ok else 'MISS'}")
print(f"REF SUMMARY correct={correct}/{len(files)}")
