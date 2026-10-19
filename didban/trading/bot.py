"""Background market worker: fetches candles for watched symbols and runs the SignalEngine."""
from __future__ import annotations

import traceback

from PySide6.QtCore import QObject, QTimer, Signal, Slot

from didban.config import TradingStyle, tf_seconds
from didban.core.signals import SignalEngine
from didban.data.exchange import MAX_CANDLES, ExchangeClient


class MarketWorker(QObject):
    data_ready = Signal(str, object, object)    # symbol, DataFrame, Signal
    error = Signal(str)
    info = Signal(str)

    def __init__(self, client: ExchangeClient, style: TradingStyle, allow_short: bool, timeframe: str, analyzer):
        super().__init__()
        self.client = client
        self.style = style
        self.timeframe = timeframe
        self.allow_short = allow_short
        self.engine = SignalEngine(style, analyzer)
        self.symbols: set[str] = set()
        self.limits: dict[str, int] = {}          # how many candles each chart wants (grows when scrolling back)
        self.timer: QTimer | None = None

    def _interval_ms(self) -> int:
        if self.client.demo:
            return 2000
        return max(5, min(self.style.refresh_sec, tf_seconds(self.timeframe) // 4)) * 1000

    @Slot()
    def start(self):
        self.timer = QTimer()
        self.timer.timeout.connect(self.tick)
        self.timer.start(self._interval_ms())
        self.tick()

    @Slot()
    def stop(self):
        if self.timer:
            self.timer.stop()

    @Slot(object)
    def set_symbols(self, symbols):
        new = set(symbols) - self.symbols
        self.symbols = set(symbols)
        self.tick(only=new)

    @Slot(str)
    def set_timeframe(self, tf: str):
        self.timeframe = tf
        self.engine = SignalEngine(self.style, _reset(self.engine.analyzer))
        if self.timer:
            self.timer.setInterval(self._interval_ms())
        self.tick()

    @Slot(object)
    def set_analyzer(self, analyzer):
        self.engine = SignalEngine(self.style, analyzer)
        self.tick()

    @Slot(str, int)
    def load_more(self, symbol: str, extra: int):
        cur = self.limits.get(symbol, 500)
        if cur >= MAX_CANDLES:
            self.info.emit(f"{symbol}: حداکثر {MAX_CANDLES} کندل بارگذاری شده است.")
            return
        self.limits[symbol] = min(MAX_CANDLES, cur + extra)
        self.tick(only={symbol})

    @Slot()
    def tick(self, only=None):
        for sym in list(only if only is not None else self.symbols):
            try:
                raw = self.client.ohlcv(sym, self.timeframe, self.limits.get(sym, 500))
                if len(raw) < 60:
                    self.error.emit(f"{sym}: داده کافی در تایم‌فریم {self.timeframe} نیست.")
                    continue
                df, sig = self.engine.analyze(sym, raw, self.allow_short, self.timeframe)
                self.data_ready.emit(sym, df, sig)
            except Exception as e:
                self.error.emit(f"{sym}: {e}")
                traceback.print_exc()


def _reset(analyzer):
    """Models are timeframe specific: drop trained state/cache."""
    if analyzer is None:
        return None
    for attr in ("models", "acc", "count", "cache"):
        if hasattr(analyzer, attr):
            getattr(analyzer, attr).clear()
    return analyzer
