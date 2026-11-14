# Card model training

Trains a card detector on the user's own deck from the app's **Learn my cards** export.

1. In the app: Home → Learn my cards → photograph all 52 cards + the empty table → Send photos.
2. Put the zip in the repo as `ml/data/my_cards.zip` and set `"data": "ml/data/my_cards.zip"` in
   `ml/train_config.json` (recommended: `"epochs": 40, "imgsz": 512, "composites": 1200`).
3. The **Train card model** workflow runs automatically: auto-labelling (`card_geometry.py`), dataset +
   synthetic table scenes (`prepare_dataset.py`), YOLO11n training (`train.py`), and a comparison with the
   current app model on held-out photos (`evaluate.py`). Results: release `card-model-candidate`
   (`report.md`, `card_detector.onnx`).
4. Only after reviewing the report is the candidate switched on in the app (`scripts/fetch_card_model.sh`).

Split rule: for every card the last 2 photos are held out for testing and are never used in training or in
the synthetic scenes. They come from the same capture session, so real-game accuracy can be lower.

`"data": "standin"` uses public single-card photos (Hugging Face `Mariofm02/cards_images`) to test the
pipeline; those photos are not used for the shipped model.
