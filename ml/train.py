"""Fine-tunes a YOLO11 detector on the prepared card dataset and exports ONNX for the app.

The app expects: input "images" [1,3,640,640], output [1, 4 + 52, anchors], class order NAMES.
"""
import argparse
import json
import shutil
from pathlib import Path

from ultralytics import YOLO

from prepare_dataset import NAMES


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="ml/build/dataset/data.yaml")
    ap.add_argument("--base", default="yolo11n.pt", help="COCO-pretrained starting weights")
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--out", default="ml/build/model")
    ap.add_argument("--runs", default="ml/build/runs", help="Ultralytics project folder")
    args = ap.parse_args()

    model = YOLO(args.base)
    model.train(
        data=args.data,
        imgsz=args.imgsz,
        epochs=args.epochs,
        batch=args.batch,
        device="cpu",
        workers=4,
        patience=10,
        project=args.runs,
        name="cards",
        exist_ok=True,
        seed=0,
        plots=False,
        # Mirroring would turn glyphs into non-existent symbols; rotation is covered by the composites.
        fliplr=0.0,
        flipud=0.0,
        degrees=10.0,
        mosaic=1.0,
    )
    best = Path(args.runs) / "cards" / "weights" / "best.pt"
    exported = Path(YOLO(str(best)).export(format="onnx", imgsz=640, opset=17, dynamic=False, simplify=False))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy(exported, out / "card_detector.onnx")
    (out / "card_detector.classes.json").write_text(json.dumps(NAMES))
    print("exported", out / "card_detector.onnx")


if __name__ == "__main__":
    main()
