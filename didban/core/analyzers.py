"""Selectable AI engines for chart analysis.

Every analyzer returns an `Analysis` with the probability of an up-move and its reasons.
Two families:
  * Local ML (free, offline): trained on the last candles of each symbol.
  * LLM (online, needs API key): the chart summary + indicators are sent as text and the model
    answers in JSON. Called only once per new candle to keep cost low.
"""
from __future__ import annotations

import json
import re
import time
import urllib.request
from dataclasses import dataclass, field

import numpy as np
import pandas as pd
from sklearn.ensemble import ExtraTreesClassifier, HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from didban.core.ai_model import FEATURES, build_features


@dataclass
class Analysis:
    prob_up: float | None
    reasons: list[str] = field(default_factory=list)
    sl: float | None = None      # optional levels suggested by an LLM
    tp: float | None = None
    accuracy: float | None = None


@dataclass(frozen=True)
class EngineInfo:
    key: str
    title: str
    kind: str                 # "none" | "ml" | "llm"
    provider: str = ""        # for llm: openai-compatible | anthropic | gemini
    base_url: str = ""
    default_model: str = ""
    needs_key: bool = False


ENGINES: dict[str, EngineInfo] = {e.key: e for e in [
    EngineInfo("indicators", "فقط اندیکاتورها (بدون هوش مصنوعی)", "none"),
    EngineInfo("hgb", "Gradient Boosting · محلی", "ml"),
    EngineInfo("rf", "Random Forest · محلی", "ml"),
    EngineInfo("et", "Extra Trees · محلی", "ml"),
    EngineInfo("logreg", "Logistic Regression · محلی", "ml"),
    EngineInfo("openai", "OpenAI GPT", "llm", "openai", "https://api.openai.com/v1", "gpt-4o-mini", True),
    EngineInfo("claude", "Anthropic Claude", "llm", "anthropic", "https://api.anthropic.com/v1", "claude-3-5-haiku-latest", True),
    EngineInfo("gemini", "Google Gemini", "llm", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash", True),
    EngineInfo("deepseek", "DeepSeek", "llm", "openai", "https://api.deepseek.com/v1", "deepseek-chat", True),
    EngineInfo("grok", "xAI Grok", "llm", "openai", "https://api.x.ai/v1", "grok-2-latest", True),
    EngineInfo("openrouter", "OpenRouter (صدها مدل)", "llm", "openai", "https://openrouter.ai/api/v1", "meta-llama/llama-3.1-70b-instruct", True),
    EngineInfo("ollama", "Ollama · محلی و رایگان", "llm", "openai", "http://localhost:11434/v1", "llama3.1", False),
]}


# ======================= local machine learning =======================
class MLAnalyzer:
    def __init__(self, kind: str, horizon: int, retrain_every: int = 20):
        self.kind, self.horizon, self.retrain_every = kind, horizon, retrain_every
        self.models: dict[str, object] = {}
        self.acc: dict[str, float | None] = {}
        self.count: dict[str, int] = {}

    def _new(self):
        if self.kind == "rf":
            return RandomForestClassifier(n_estimators=200, max_depth=6, min_samples_leaf=5, n_jobs=-1, random_state=7)
        if self.kind == "et":
            return ExtraTreesClassifier(n_estimators=250, max_depth=7, min_samples_leaf=5, n_jobs=-1, random_state=7)
        if self.kind == "logreg":
            return make_pipeline(StandardScaler(), LogisticRegression(C=0.5, max_iter=500))
        return HistGradientBoostingClassifier(max_iter=150, learning_rate=0.06, max_depth=4, l2_regularization=1.0, random_state=7)

    def _fit(self, symbol: str, df: pd.DataFrame):
        feats = build_features(df)
        fut = df["close"].shift(-self.horizon) / df["close"] - 1
        label = (fut > 0.3 * df["atr"] / df["close"]).astype(int)
        data = feats.assign(y=label, fut=fut).dropna()
        if len(data) < 150 or data["y"].nunique() < 2:
            return
        split = int(len(data) * 0.8)
        m = self._new()
        m.fit(data[FEATURES].iloc[:split], data["y"].iloc[:split])
        test = data.iloc[split:]
        self.acc[symbol] = float((m.predict(test[FEATURES]) == test["y"]).mean()) if len(test) else None
        m = self._new()
        m.fit(data[FEATURES], data["y"])
        self.models[symbol] = m

    def analyze(self, symbol: str, df: pd.DataFrame, timeframe: str) -> Analysis:
        n = self.count.get(symbol, 0)
        if symbol not in self.models or n % self.retrain_every == 0:
            self._fit(symbol, df)
        self.count[symbol] = n + 1
        m = self.models.get(symbol)
        if m is None:
            return Analysis(None, ["داده کافی برای آموزش مدل نیست"])
        row = build_features(df).iloc[[-1]]
        if row.isna().any(axis=None):
            return Analysis(None)
        p = float(m.predict_proba(row[FEATURES])[0, 1])
        acc = self.acc.get(symbol)
        txt = f"احتمال صعود ({ENGINES[self.kind].title.split(' ·')[0]}): {p*100:.0f}%"
        if acc is not None:
            txt += f" · دقت بک‌تست {acc*100:.0f}%"
        return Analysis(p, [txt], accuracy=acc)


# ======================= large language models =======================
PROMPT = """You are a professional crypto technical analyst. Analyze the chart data below for {symbol} on the {tf} timeframe.
Indicators on the last candle: {ind}
Last {n} candles (time, open, high, low, close, volume), oldest first:
{rows}

Answer ONLY with JSON, no other text:
{{"prob_up": <0..1 probability price is higher in the next {h} candles>,
  "stop_loss": <price or null>, "take_profit": <price or null>,
  "reasons": ["<short reason in Persian>", "..."]}}"""


def _post(url: str, headers: dict, body: dict, timeout: int = 60) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


class LLMAnalyzer:
    def __init__(self, info: EngineInfo, api_key: str, model: str = "", base_url: str = "", horizon: int = 8, candles: int = 60):
        self.info, self.key = info, api_key
        self.model = model or info.default_model
        self.base = (base_url or info.base_url).rstrip("/")
        self.horizon, self.candles = horizon, candles
        self.cache: dict[str, tuple[int, Analysis]] = {}   # symbol -> (candle time, result)

    def _ask(self, prompt: str) -> str:
        p = self.info.provider
        if p == "anthropic":
            r = _post(f"{self.base}/messages", {"x-api-key": self.key, "anthropic-version": "2023-06-01"},
                      {"model": self.model, "max_tokens": 600, "messages": [{"role": "user", "content": prompt}]})
            return r["content"][0]["text"]
        if p == "gemini":
            r = _post(f"{self.base}/models/{self.model}:generateContent?key={self.key}", {},
                      {"contents": [{"parts": [{"text": prompt}]}]})
            return r["candidates"][0]["content"]["parts"][0]["text"]
        headers = {"Authorization": f"Bearer {self.key}"} if self.key else {}
        r = _post(f"{self.base}/chat/completions", headers,
                  {"model": self.model, "temperature": 0.2, "messages": [{"role": "user", "content": prompt}]})
        return r["choices"][0]["message"]["content"]

    def analyze(self, symbol: str, df: pd.DataFrame, timeframe: str) -> Analysis:
        candle_t = int(df["time"].iloc[-2]) if len(df) > 1 else 0   # last CLOSED candle
        hit = self.cache.get(symbol)
        if hit and hit[0] == candle_t:
            return hit[1]
        last = df.iloc[-1]
        ind = (f"EMA_fast={last['ema_fast']:.6g}, EMA_slow={last['ema_slow']:.6g}, RSI={last['rsi']:.1f}, "
               f"MACD_hist={last['macd_hist']:.6g}, ATR={last['atr']:.6g}, ADX={last['adx']:.1f}, "
               f"BB_low={last['bb_low']:.6g}, BB_up={last['bb_up']:.6g}")
        seg = df.iloc[-self.candles:]
        rows = "\n".join(f"{int(r.time)},{r.open:.6g},{r.high:.6g},{r.low:.6g},{r.close:.6g},{r.volume:.4g}" for r in seg.itertuples())
        prompt = PROMPT.format(symbol=symbol, tf=timeframe, ind=ind, n=len(seg), rows=rows, h=self.horizon)
        try:
            text = self._ask(prompt)
            m = re.search(r"\{.*\}", text, re.S)
            data = json.loads(m.group(0)) if m else {}
            p = float(np.clip(float(data.get("prob_up", 0.5)), 0, 1))
            reasons = [f"{self.info.title}: احتمال صعود {p*100:.0f}%"] + [str(x) for x in data.get("reasons", [])][:4]
            res = Analysis(p, reasons, _num(data.get("stop_loss")), _num(data.get("take_profit")))
        except Exception as e:  # network / quota / parse errors must not stop the bot
            res = Analysis(None, [f"خطای {self.info.title}: {str(e)[:120]}"])
            candle_t = -int(time.time())   # retry next tick
        self.cache[symbol] = (candle_t, res)
        return res


def _num(v):
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def create(key: str, horizon: int, api_key: str = "", model: str = "", base_url: str = ""):
    info = ENGINES[key]
    if info.kind == "none":
        return None
    if info.kind == "ml":
        return MLAnalyzer(key, horizon)
    return LLMAnalyzer(info, api_key, model, base_url, horizon)
