"""Downloads a few public playing-card photos for an on-device recognition smoke test.

Source: Hugging Face dataset Mariofm02/cards_images (one card per image). Files are written to
core/vision/src/androidTest/assets/samples/<CODE>_<n>.jpg, e.g. AS_0.jpg. Failures are not fatal:
the instrumented test is skipped when no samples exist.
"""
import json, os, random, sys, urllib.request

DATASET = "Mariofm02/cards_images"
DEST = os.path.join(os.path.dirname(__file__), "..", "core", "vision", "src", "androidTest", "assets", "samples")
RANKS = {"ace": "A", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6", "seven": "7", "eight": "8",
         "nine": "9", "ten": "10", "jack": "J", "queen": "Q", "king": "K"}
SUITS = {"clubs": "C", "diamonds": "D", "hearts": "H", "spades": "S"}

def get(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        return r.read()

def code_for(name):
    parts = name.lower().split(" of ")
    if len(parts) != 2 or parts[0] not in RANKS or parts[1] not in SUITS:
        return None
    return RANKS[parts[0]] + SUITS[parts[1]]

def main():
    os.makedirs(DEST, exist_ok=True)
    random.seed(7)
    base = f"https://datasets-server.huggingface.co/rows?dataset={DATASET}&config=default&split=train"
    first = json.loads(get(base + "&offset=0&length=1"))
    names = next(f["type"]["names"] for f in first["features"] if f["name"] == "label")
    total = first.get("num_rows_total", 1000)
    saved = 0
    for offset in random.sample(range(total), 40):
        if saved >= 16:
            break
        row = json.loads(get(base + f"&offset={offset}&length=1"))["rows"][0]["row"]
        code = code_for(names[row["label"]])
        if code is None:
            continue
        data = get(row["image"]["src"])
        with open(os.path.join(DEST, f"{code}_{saved}.jpg"), "wb") as f:
            f.write(data)
        saved += 1
        print("sample", code, len(data), "bytes")
    print("saved", saved)

if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # network/dataset problems must not break the build
        print("sample download failed:", e, file=sys.stderr)
