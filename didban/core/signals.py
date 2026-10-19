"""Combines indicator votes + the selected AI engine into an entry/exit signal with SL/TP."""
from __future__ import annotations

from dataclasses import dataclass, field

import pandas as pd

from didban.config import TradingStyle
from didban.core import indicators as ind


@dataclass
class Signal:
    symbol: str
    side: str            # "long", "short" or "flat"
    confidence: float    # 0..1
    entry: float
    stop_loss: float
    take_profit: float
    ai_prob_up: float | None
    reasons: list[str] = field(default_factory=list)
    time: int = 0

    @property
    def actionable(self) -> bool:
        return self.side in ("long", "short")


class SignalEngine:
    def __init__(self, style: TradingStyle, analyzer=None, ai_weight: float = 0.55):
        self.style = style
        self.analyzer = analyzer       # None = indicators only
        self.ai_weight = ai_weight

    def analyze(self, symbol: str, raw: pd.DataFrame, allow_short: bool, timeframe: str = "") -> tuple[pd.DataFrame, Signal]:
        st = self.style
        df = ind.add_all(raw, st.ema_fast, st.ema_slow)
        last = df.iloc[-1]
        price, a = float(last["close"]), float(last["atr"])
        votes, reasons = 0.0, []
        if last["ema_fast"] > last["ema_slow"]:
            votes += 1; reasons.append(f"EMA{st.ema_fast} بالای EMA{st.ema_slow} (روند صعودی)")
        else:
            votes -= 1; reasons.append(f"EMA{st.ema_fast} زیر EMA{st.ema_slow} (روند نزولی)")
        if last["macd_hist"] > 0:
            votes += 0.5; reasons.append("هیستوگرام MACD مثبت")
        else:
            votes -= 0.5; reasons.append("هیستوگرام MACD منفی")
        r = float(last["rsi"])
        if r > 70:
            votes -= 0.75; reasons.append(f"RSI اشباع خرید ({r:.0f})")
        elif r < 30:
            votes += 0.75; reasons.append(f"RSI اشباع فروش ({r:.0f})")
        weak = last["adx"] < 18
        if weak:
            reasons.append("ADX پایین، بازار بدون روند (احتیاط)")
        tech = (votes / 2.25 + 1) / 2

        res = self.analyzer.analyze(symbol, df, timeframe or st.timeframe) if self.analyzer else None
        p_up = res.prob_up if res else None
        if res:
            reasons += res.reasons
        prob = tech if p_up is None else (1 - self.ai_weight) * tech + self.ai_weight * p_up

        side, conf = "flat", max(prob, 1 - prob)
        if prob >= st.min_confidence and not weak:
            side = "long"
        elif allow_short and (1 - prob) >= st.min_confidence and not weak:
            side = "short"
        if side == "short":
            sl, tp = price + a * st.sl_atr, price - a * st.tp_atr
        else:
            sl, tp = price - a * st.sl_atr, price + a * st.tp_atr
        # accept LLM levels only when they are on the correct side of price
        if res and res.sl and res.tp and side != "flat":
            ok = (res.sl < price < res.tp) if side == "long" else (res.tp < price < res.sl)
            if ok:
                sl, tp = res.sl, res.tp
                reasons.append("حد سود و ضرر پیشنهادی هوش مصنوعی اعمال شد")
        return df, Signal(symbol, side, conf, price, sl, tp, p_up, reasons, int(last["time"]))
