"""Card outline detection and corner-index boxes for auto-labelling the user's card photos.

The capture guide asks for one card lying flat inside the square, so the card is the largest bright
convex quadrilateral in the photo. Its corner index (rank + suit) sits in the top-left and
bottom-right corners of a standard poker card; we label those regions through the card's perspective.
"""
from __future__ import annotations

import cv2
import numpy as np

# Poker card 63 x 88 mm.
CARD_ASPECT = 88.0 / 63.0
CANON_W, CANON_H = 252, 352

# Corner index region in card-relative coordinates (u across, v down). Generous enough for the usual
# deck layouts (rank + suit symbol in the top-left ~15 % x 25 % of the card).
INDEX_TL = (0.02, 0.02, 0.21, 0.31)
INDEX_BR = (1 - INDEX_TL[2], 1 - INDEX_TL[3], 1 - INDEX_TL[0], 1 - INDEX_TL[1])


def order_quad(pts: np.ndarray) -> np.ndarray:
    """Orders 4 corners clockwise (image coordinates) starting so the first edge is a short side.

    Sorting by angle around the centre is robust at any rotation (the x+y / y-x trick fails for cards
    lying near 45 degrees). Which of the two short sides comes first does not matter: card indices sit
    in diagonally opposite corners, so both choices give the same two index regions.
    """
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    c = pts.mean(axis=0)
    angles = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    quad = pts[np.argsort(angles)]  # increasing angle = clockwise on screen (y points down)
    if np.linalg.norm(quad[1] - quad[0]) > np.linalg.norm(quad[2] - quad[1]):
        quad = np.roll(quad, -1, axis=0)
    return quad.astype(np.float32)


def _quad_ok(quad: np.ndarray, image_area: float) -> bool:
    w = (np.linalg.norm(quad[1] - quad[0]) + np.linalg.norm(quad[2] - quad[3])) / 2
    h = (np.linalg.norm(quad[3] - quad[0]) + np.linalg.norm(quad[2] - quad[1])) / 2
    if w < 1 or h < 1:
        return False
    aspect = h / w
    area = cv2.contourArea(quad)
    return 1.15 <= aspect <= 1.75 and area >= 0.03 * image_area


def find_card_quad(img: np.ndarray) -> np.ndarray | None:
    """Returns the card corners (TL, TR, BR, BL; portrait) in pixel coordinates, or None."""
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    blur = cv2.GaussianBlur(gray, (5, 5), 0)
    _, th = cv2.threshold(blur, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    th = cv2.morphologyEx(th, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8), iterations=2)
    contours, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:5]:
        if cv2.contourArea(c) < 0.03 * w * h:
            break
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) == 4 and cv2.isContourConvex(approx):
            quad = order_quad(approx)
        else:
            rect = cv2.minAreaRect(c)
            if cv2.contourArea(c) / max(rect[1][0] * rect[1][1], 1) < 0.85:
                continue  # not rectangular enough (e.g. a hand holding the card)
            quad = order_quad(cv2.boxPoints(rect))
        # A contour touching the whole image border is the background, not a card.
        if _quad_ok(quad, w * h) and cv2.contourArea(quad) < 0.97 * w * h:
            return quad
    # Fallback for tight crops where the card fills the image (some public datasets).
    if 1.2 <= h / w <= 1.6:
        return order_quad(np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]]))
    return None


def card_homography(quad: np.ndarray) -> np.ndarray:
    """Maps canonical card coordinates (pixels of a CANON_W x CANON_H card) to the image."""
    src = np.array([[0, 0], [CANON_W, 0], [CANON_W, CANON_H], [0, CANON_H]], dtype=np.float32)
    return cv2.getPerspectiveTransform(src, quad.astype(np.float32))


def region_box(H: np.ndarray, region: tuple[float, float, float, float], img_w: int, img_h: int, clip: bool = True):
    """Axis-aligned box (x1, y1, x2, y2) in image pixels of a card-relative region (clipped unless
    clip=False); None if the clipped box is empty."""
    u1, v1, u2, v2 = region
    pts = np.array([[u1, v1], [u2, v1], [u2, v2], [u1, v2]], dtype=np.float32)
    pts *= np.array([CANON_W, CANON_H], dtype=np.float32)
    mapped = cv2.perspectiveTransform(pts.reshape(-1, 1, 2), H).reshape(-1, 2)
    lo, hi = mapped.min(axis=0), mapped.max(axis=0)
    if not clip:
        return float(lo[0]), float(lo[1]), float(hi[0]), float(hi[1])
    x1, y1 = np.clip(lo, 0, [img_w - 1, img_h - 1])
    x2, y2 = np.clip(hi, 0, [img_w - 1, img_h - 1])
    if x2 - x1 < 2 or y2 - y1 < 2:
        return None
    return float(x1), float(y1), float(x2), float(y2)


def index_boxes(quad: np.ndarray, img_w: int, img_h: int):
    H = card_homography(quad)
    return [b for b in (region_box(H, INDEX_TL, img_w, img_h), region_box(H, INDEX_BR, img_w, img_h)) if b]


def extract_card(img: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Upright CANON_W x CANON_H crop of the card."""
    H = card_homography(quad)
    return cv2.warpPerspective(img, np.linalg.inv(H), (CANON_W, CANON_H), flags=cv2.INTER_LINEAR)
