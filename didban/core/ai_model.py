"""Lightweight machine-learning model that estimates the probability of an up-move.

It is retrained on the most recent candles of each symbol (walk-forward), so it adapts
to the current market regime. It is intentionally simple and fast: a gradient boosting
classifier from scikit-learn on indicator-based features.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier

FEATURES = ["ret1", "ret3", "ret6", "rsi", "macd_hist_n", "ema_gap", "atr_pct",
            "bb_pos", "adx", "vol_z"]


def build_features(df: pd.DataFrame) -> pd.DataFrame:
    f = pd.DataFrame(index=df.index)
    c = df["close"]
    f["ret1"] = c.pct_change(1)
    f["ret3"] = c.pct_change(3)
    f["ret6"] = c.pct_change(6)
    f["rsi"] = df["rsi"] / 100
    f["macd_hist_n"] = df["macd_hist"] / c
    f["ema_gap"] = (df["ema_fast"] - df["ema_slow"]) / c
    f["atr_pct"] = df["atr"] / c
    width = (df["bb_up"] - df["bb_low"]).replace(0, np.nan)
    f["bb_pos"] = (c - df["bb_low"]) / width
    f["adx"] = df["adx"] / 100
    f["vol_z"] = df["vol_z"].clip(-5, 5)
    return f.replace([np.inf, -np.inf], np.nan)


class AIModel:
    def __init__(self, horizon: int = 8, min_train: int = 150):
        self.horizon = horizon
        self.min_train = min_train
        self.model: HistGradientBoostingClassifier | None = None
        self.accuracy: float | None = None   # out-of-sample accuracy on the last chunk

    def fit(self, df: pd.DataFrame) -> bool:
        feats = build_features(df)
        fut = df["close"].shift(-self.horizon) / df["close"] - 1
        # label = 1 if price moved up more than 0.3 ATR within the horizon
        thr = 0.3 * df["atr"] / df["close"]
        label = (fut > thr).astype(int)
        data = feats.assign(y=label, fut=fut).dropna()
        if len(data) < self.min_train:
            return False
        split = int(len(data) * 0.8)
        train, test = data.iloc[:split], data.iloc[split:]
        m = HistGradientBoostingClassifier(max_iter=150, learning_rate=0.06, max_depth=4,
                                           l2_regularization=1.0, random_state=7)
        m.fit(train[FEATURES], train["y"])
        self.accuracy = float((m.predict(test[FEATURES]) == test["y"]).mean()) if len(test) else None
        m.fit(data[FEATURES], data["y"])  # refit on everything for the live prediction
        self.model = m
        return True

    def predict_up(self, df: pd.DataFrame) -> float | None:
        if self.model is None:
            return None
        row = build_features(df).iloc[[-1]]
        if row.isna().any(axis=None):
            return None
        return float(self.model.predict_proba(row[FEATURES])[0, 1])
