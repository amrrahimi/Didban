"""Market data + orders through ccxt, with an offline demo feed.

* Any timeframe: native if the exchange supports it, otherwise resampled from a smaller one.
* Deep history: requests with limit > one page are fetched page by page.
"""
from __future__ import annotations

import time

import numpy as np
import pandas as pd

from didban.config import DEFAULT_SYMBOLS, EXCHANGES, tf_seconds

try:
    import ccxt  # type: ignore
except ImportError:  # app still works in demo mode
    ccxt = None

COLUMNS = ["time", "open", "high", "low", "close", "volume"]
MAX_CANDLES = 5000


def available_exchanges() -> list[str]:
    names = []
    for name, (spot, fut) in EXCHANGES.items():
        if spot is None:
            names.append(name)
        elif ccxt is not None and (spot in ccxt.exchanges or fut in ccxt.exchanges):
            names.append(name)
    return names


def resample(df: pd.DataFrame, tf: str) -> pd.DataFrame:
    """Build bigger candles (e.g. 7m from 1m). Bucket alignment is by epoch."""
    step = tf_seconds(tf) * 1000
    g = df.groupby(df["time"] // step * step)
    out = pd.DataFrame({"open": g["open"].first(), "high": g["high"].max(), "low": g["low"].min(),
                        "close": g["close"].last(), "volume": g["volume"].sum()})
    out.index.name = "time"
    return out.reset_index()[COLUMNS]


class DemoFeed:
    """Random-walk candles so the app can be explored without internet or API keys."""

    PRICES = [67000, 3100, 580, 150, 0.55, 0.12, 5.4, 0.38, 0.16, 28, 0.000018, 13, 5.2, 360, 1.9, 70,
              4.8, 0.0000095, 8.5, 7.2, 9.0, 19, 0.095, 3.6, 4.4, 0.55, 1.4, 21, 0.06, 5.1, 380, 1.8]
    BASE = dict(zip(DEFAULT_SYMBOLS, PRICES))

    def __init__(self):
        self.cache: dict[tuple[str, str], pd.DataFrame] = {}

    def symbols(self) -> list[str]:
        extra = ["SEI/USDT", "TIA/USDT", "JUP/USDT", "FET/USDT", "GRT/USDT", "AAVE/USDT", "MKR/USDT",
                 "ALGO/USDT", "SAND/USDT", "MANA/USDT", "AXS/USDT", "FLOKI/USDT", "BONK/USDT", "NOT/USDT"]
        return list(self.BASE) + extra

    def _generate(self, symbol: str, tf: str, n: int) -> pd.DataFrame:
        step = tf_seconds(tf) * 1000
        rng = np.random.default_rng(abs(hash((symbol, tf))) % 2**32)
        p, rows = self.BASE.get(symbol, 1.0 + (abs(hash(symbol)) % 500) / 10), []
        t0 = (int(time.time() * 1000) // step - n) * step
        for i in range(n):
            o = p
            c = o * (1 + 0.0015 * np.sin(i / 25) + rng.normal(0, 0.008))
            h = max(o, c) * (1 + abs(rng.normal(0, 0.003)))
            lo = min(o, c) * (1 - abs(rng.normal(0, 0.003)))
            rows.append([t0 + i * step, o, h, lo, c, abs(rng.normal(1000, 300))])
            p = c
        return pd.DataFrame(rows, columns=COLUMNS)

    def ohlcv(self, symbol: str, tf: str, limit: int = 500) -> pd.DataFrame:
        key = (symbol, tf)
        step = tf_seconds(tf) * 1000
        if key not in self.cache or len(self.cache[key]) < limit:
            self.cache[key] = self._generate(symbol, tf, max(limit, 500))
        df = self.cache[key]
        last = df.iloc[-1].copy()
        now_bucket = int(time.time() * 1000) // step * step
        rng = np.random.default_rng()
        if now_bucket > last["time"]:
            new = [now_bucket, last["close"], last["close"], last["close"], last["close"], 0.0]
            df = pd.concat([df, pd.DataFrame([new], columns=COLUMNS)], ignore_index=True)
            last = df.iloc[-1].copy()
        c = last["close"] * (1 + rng.normal(0, 0.0015))
        df.loc[df.index[-1], ["close", "high", "low", "volume"]] = [
            c, max(last["high"], c), min(last["low"], c), last["volume"] + abs(rng.normal(30, 10))]
        self.cache[key] = df.reset_index(drop=True)
        return self.cache[key].iloc[-limit:].reset_index(drop=True).copy()


class ExchangeClient:
    def __init__(self, name: str, market_type: str = "spot", api_key: str = "", secret: str = "",
                 password: str = "", testnet: bool = False):
        self.name = name
        self.market_type = market_type
        self.demo = EXCHANGES[name][0] is None
        self.feed = DemoFeed() if self.demo else None
        self.has_keys = bool(api_key)
        self.ex = None
        if self.demo:
            return
        if ccxt is None:
            raise RuntimeError("کتابخانه ccxt نصب نیست: pip install ccxt")
        spot_id, fut_id = EXCHANGES[name]
        ex_id = fut_id if market_type == "futures" else spot_id
        if ex_id not in ccxt.exchanges:
            raise RuntimeError(f"نسخه نصب‌شده ccxt از {name} پشتیبانی نمی‌کند. دستور: pip install -U ccxt")
        opts = {"enableRateLimit": True, "options": {}}
        if market_type == "futures":
            opts["options"]["defaultType"] = "swap"
        elif market_type == "margin":
            opts["options"]["defaultType"] = "margin"
        if api_key:
            opts.update(apiKey=api_key, secret=secret)
            if password:
                opts["password"] = password  # KuCoin / OKX / Bitget passphrase
        self.ex = getattr(ccxt, ex_id)(opts)
        if testnet:
            try:
                self.ex.set_sandbox_mode(True)
            except Exception as e:
                raise RuntimeError(f"{name} تست‌نت عمومی ندارد ({e}). از حساب تست داخلی استفاده کنید.")
        self._markets = None

    # ---------------- connection ----------------
    def test(self) -> str:
        """Check public + private access. Returns a human readable status."""
        if self.demo:
            return "حالت دمو، نیازی به API نیست."
        self.ex.load_markets()
        if not self.has_keys:
            return "اتصال عمومی برقرار است (بدون کلید، فقط داده بازار)."
        bal = self.ex.fetch_balance()
        usdt = bal.get("USDT", {}) or {}
        return f"کلید معتبر است ✓  موجودی USDT: آزاد {usdt.get('free') or 0:,.2f} · کل {usdt.get('total') or 0:,.2f}"

    # ---------------- data ----------------
    def symbols(self, quote: str = "USDT") -> list[str]:
        if self.demo:
            return self.feed.symbols()
        markets = self._markets = self.ex.load_markets()
        want_swap = self.market_type == "futures"
        out = []
        for s, m in markets.items():
            if m.get("quote") != quote or m.get("active") is False:
                continue
            if (want_swap and m.get("swap")) or (not want_swap and m.get("spot")):
                out.append(s)
        return sorted(out)

    def native_timeframes(self) -> list[str]:
        if self.demo or not getattr(self.ex, "timeframes", None):
            return []
        return list(self.ex.timeframes.keys())

    def _fetch(self, symbol: str, tf: str, limit: int) -> pd.DataFrame:
        """Fetch `limit` candles, paging backwards when needed."""
        page = 1000
        step = tf_seconds(tf) * 1000
        if limit <= page:
            rows = self.ex.fetch_ohlcv(symbol, tf, limit=limit)
            return pd.DataFrame(rows, columns=COLUMNS)
        since = int(time.time() * 1000) - limit * step
        rows: list = []
        while since < time.time() * 1000 and len(rows) < limit + page:
            chunk = self.ex.fetch_ohlcv(symbol, tf, since=since, limit=page)
            if not chunk:
                break
            rows += chunk
            nxt = chunk[-1][0] + step
            if nxt <= since:
                break
            since = nxt
        df = pd.DataFrame(rows, columns=COLUMNS).drop_duplicates("time").sort_values("time")
        return df.iloc[-limit:].reset_index(drop=True)

    def ohlcv(self, symbol: str, tf: str, limit: int = 500) -> pd.DataFrame:
        limit = min(limit, MAX_CANDLES)
        if self.demo:
            return self.feed.ohlcv(symbol, tf, limit)
        native = self.native_timeframes()
        if not native or tf in native:
            return self._fetch(symbol, tf, limit)
        # custom timeframe: pick the largest native timeframe that divides it
        target = tf_seconds(tf)
        cands = [t for t in native if _safe_sec(t) and target % _safe_sec(t) == 0 and _safe_sec(t) < target]
        if not cands:
            raise RuntimeError(f"تایم‌فریم {tf} روی {self.name} قابل ساخت نیست.")
        base = max(cands, key=_safe_sec)
        factor = target // _safe_sec(base)
        raw = self._fetch(symbol, base, min(limit * factor + factor, MAX_CANDLES * 3))
        return resample(raw, tf).iloc[-limit:].reset_index(drop=True)

    # ---------------- trading ----------------
    def balance_usdt(self) -> float:
        if self.demo:
            return 0.0
        bal = self.ex.fetch_balance()
        return float(bal.get("USDT", {}).get("free") or 0)

    def prepare_leverage(self, symbol: str, leverage: int, margin_mode: str = "isolated"):
        if self.demo or self.market_type == "spot":
            return
        for fn, args in (("set_margin_mode", (margin_mode, symbol)), ("set_leverage", (leverage, symbol))):
            try:
                getattr(self.ex, fn)(*args)
            except Exception:
                pass

    def open_position(self, symbol: str, side: str, amount: float, sl: float, tp: float) -> dict:
        order_side = "buy" if side == "long" else "sell"
        exit_side = "sell" if side == "long" else "buy"
        amount = float(self.ex.amount_to_precision(symbol, amount))
        params = {"stopLoss": {"triggerPrice": sl}, "takeProfit": {"triggerPrice": tp}}
        if self.market_type == "margin":
            params["marginMode"] = "isolated"
        try:
            return self.ex.create_order(symbol, "market", order_side, amount, None, params)
        except Exception:
            order = self.ex.create_order(symbol, "market", order_side, amount)
            extra = {"reduceOnly": True} if self.market_type == "futures" else {}
            self.ex.create_order(symbol, "market", exit_side, amount, None, {**extra, "stopLossPrice": sl})
            self.ex.create_order(symbol, "market", exit_side, amount, None, {**extra, "takeProfitPrice": tp})
            return order

    def close_position(self, symbol: str, side: str, amount: float) -> dict:
        exit_side = "sell" if side == "long" else "buy"
        params = {"reduceOnly": True} if self.market_type == "futures" else {}
        return self.ex.create_order(symbol, "market", exit_side, amount, None, params)


def _safe_sec(tf: str) -> int:
    try:
        return tf_seconds(tf)
    except ValueError:
        return 0
