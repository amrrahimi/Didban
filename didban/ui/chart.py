"""Candlestick chart (pyqtgraph).

Navigation:
  * drag with the mouse  -> scroll through past candles
  * mouse wheel          -> zoom in / out (time axis)
  * drag on price axis   -> stretch prices vertically; double-click -> auto again
  * scrolling near the left edge loads older history automatically
  * "زنده" button returns to the latest candle and follows price
Overlays: EMA, RSI, AI entry/exit marks, position entry / TP / SL / liquidation lines, crosshair with OHLC.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pyqtgraph as pg
from PySide6.QtCore import QPointF, QRectF, Qt, Signal
from PySide6.QtGui import QBrush, QColor, QPainter, QPen, QPicture
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

from didban.ui.theme import EMBER, NAVY, RED, SKY, TEAL

pg.setConfigOptions(antialias=True, background="w", foreground="#5f7470")


class CandlestickItem(pg.GraphicsObject):
    def __init__(self):
        super().__init__()
        self.picture = QPicture()

    def set_data(self, t, o, h, l, c, width):
        pic = QPicture()
        p = QPainter(pic)
        for ti, oi, hi, li, ci in zip(t, o, h, l, c):
            col = QColor(TEAL if ci >= oi else RED)
            p.setPen(QPen(col, 0))
            p.drawLine(QPointF(ti, li), QPointF(ti, hi))
            p.setBrush(QBrush(col))
            p.drawRect(QRectF(ti - width / 2, min(oi, ci), width, max(abs(ci - oi), 1e-12)))
        p.end()
        self.prepareGeometryChange()
        self.picture = pic
        self.update()

    def paint(self, p, *args):
        p.drawPicture(0, 0, self.picture)

    def boundingRect(self):
        return QRectF(self.picture.boundingRect())


class PriceAxisVB(pg.ViewBox):
    """Mouse drag pans time only; y is auto-fitted to the visible candles unless the user stretches it."""

    def __init__(self, chart):
        super().__init__()
        self.chart = chart
        self.setMouseEnabled(x=True, y=False)

    def mouseDoubleClickEvent(self, ev):
        self.chart.manual_y = False
        self.chart.fit_y()
        ev.accept()


class ChartView(QWidget):
    need_history = Signal(str, int)

    def __init__(self, symbol: str, parent=None):
        super().__init__(parent)
        self.symbol = symbol
        self.df: pd.DataFrame | None = None
        self.follow = True
        self.manual_y = False
        self._loading = False
        self._first = True
        self.trade_marks: list[dict] = []
        self.pos_lines: list[pg.InfiniteLine] = []

        lay = QVBoxLayout(self)
        lay.setContentsMargins(4, 4, 4, 4)
        bar = QHBoxLayout()
        self.lb_ohlc = QLabel("")
        self.lb_ohlc.setStyleSheet("font-family: Consolas, Menlo, monospace; color: #5f7470;")
        self.btn_live = QPushButton("⏵ زنده")
        self.btn_live.setObjectName("ghost")
        self.btn_live.clicked.connect(self.go_live)
        hint = QLabel("کشیدن: کندل‌های گذشته · اسکرول: زوم · دوبار کلیک: تنظیم خودکار")
        hint.setObjectName("muted")
        bar.addWidget(self.lb_ohlc, 1)
        bar.addWidget(hint)
        bar.addWidget(self.btn_live)
        lay.addLayout(bar)

        self.glw = pg.GraphicsLayoutWidget()
        lay.addWidget(self.glw)
        self.vb = PriceAxisVB(self)
        self.price = self.glw.addPlot(row=0, col=0, viewBox=self.vb, axisItems={"bottom": pg.DateAxisItem()})
        self.price.showGrid(x=True, y=True, alpha=0.15)
        self.price.setMenuEnabled(False)
        self.price.showAxis("right")
        self.price.hideAxis("left")
        self.rsi_plot = self.glw.addPlot(row=1, col=0, axisItems={"bottom": pg.DateAxisItem()})
        self.rsi_plot.setMaximumHeight(110)
        self.rsi_plot.setXLink(self.price)
        self.rsi_plot.setMouseEnabled(x=True, y=False)
        self.rsi_plot.setYRange(0, 100)
        self.rsi_plot.showAxis("right")
        self.rsi_plot.hideAxis("left")
        for lvl in (30, 70):
            self.rsi_plot.addItem(pg.InfiniteLine(lvl, angle=0, pen=pg.mkPen("#c7d6d2", style=Qt.DashLine)))

        self.candles = CandlestickItem()
        self.price.addItem(self.candles)
        self.ema_f = self.price.plot(pen=pg.mkPen(SKY, width=1.6))
        self.ema_s = self.price.plot(pen=pg.mkPen(NAVY, width=1.6, style=Qt.DashLine))
        self.rsi_line = self.rsi_plot.plot(pen=pg.mkPen(SKY, width=1.4))
        self.marks = pg.ScatterPlotItem(size=13)
        self.price.addItem(self.marks)

        # crosshair
        self.vline = pg.InfiniteLine(angle=90, movable=False, pen=pg.mkPen("#9fb3ae", style=Qt.DotLine))
        self.hline = pg.InfiniteLine(angle=0, movable=False, pen=pg.mkPen("#9fb3ae", style=Qt.DotLine))
        self.price.addItem(self.vline, ignoreBounds=True)
        self.price.addItem(self.hline, ignoreBounds=True)
        self.proxy = pg.SignalProxy(self.price.scene().sigMouseMoved, rateLimit=40, slot=self._mouse)

        self.vb.sigXRangeChanged.connect(self._x_changed)
        # stretching the price axis by dragging it = manual y
        self.price.getAxis("right").mouseDragEvent = self._axis_drag

    # -------- navigation --------
    def _axis_drag(self, ev):
        self.manual_y = True
        ev.accept()
        if ev.isStart() or ev.isFinish():
            return
        dy = ev.pos().y() - ev.lastPos().y()
        self.vb.scaleBy(y=1 + dy * 0.01)

    def _visible(self):
        if self.df is None or self.df.empty:
            return None
        (x0, x1), _ = self.vb.viewRange()
        t = self.df["time"].to_numpy() / 1000
        m = (t >= x0) & (t <= x1)
        return self.df[m] if m.any() else None

    def fit_y(self):
        seg = self._visible()
        if seg is None or self.manual_y:
            return
        lo, hi = float(seg["low"].min()), float(seg["high"].max())
        pad = (hi - lo) * 0.08 or hi * 0.01
        self.vb.setYRange(lo - pad, hi + pad, padding=0)

    def _x_changed(self, *_):
        if self.df is None or self.df.empty:
            return
        t = self.df["time"].to_numpy() / 1000
        (x0, x1), _ = self.vb.viewRange()
        step = t[1] - t[0] if len(t) > 1 else 60
        self.follow = x1 >= t[-1] - step
        self.btn_live.setEnabled(not self.follow)
        self.fit_y()
        if x0 < t[0] + step * 30 and not self._loading:
            self._loading = True
            self.need_history.emit(self.symbol, 500)

    def go_live(self):
        self.follow = True
        self.manual_y = False
        self._show_last(self._span())

    def _span(self) -> int:
        if self.df is None or len(self.df) < 2:
            return 150
        (x0, x1), _ = self.vb.viewRange()
        step = (self.df["time"].iloc[1] - self.df["time"].iloc[0]) / 1000
        return int(max(20, min(len(self.df), (x1 - x0) / step)))

    def _show_last(self, n: int):
        t = self.df["time"].to_numpy() / 1000
        step = t[1] - t[0] if len(t) > 1 else 60
        n = min(n, len(t))
        self.vb.setXRange(t[-n], t[-1] + step * 5, padding=0)
        self.fit_y()

    def _mouse(self, evt):
        pos = evt[0]
        if self.df is None or not self.price.sceneBoundingRect().contains(pos):
            return
        p = self.vb.mapSceneToView(pos)
        self.vline.setPos(p.x())
        self.hline.setPos(p.y())
        t = self.df["time"].to_numpy() / 1000
        i = int(np.clip(np.searchsorted(t, p.x()), 0, len(t) - 1))
        r = self.df.iloc[i]
        ts = pd.to_datetime(int(r["time"]), unit="ms").strftime("%Y-%m-%d %H:%M")
        self.lb_ohlc.setText(f"{self.symbol}  {ts}   O {r['open']:.6g}  H {r['high']:.6g}  L {r['low']:.6g}  C {r['close']:.6g}")

    # -------- data --------
    def reset_view(self):
        self._first = True

    def update_data(self, df: pd.DataFrame):
        grew = self.df is not None and len(df) > len(self.df) + 5
        span = self._span()
        self.df = df
        self._loading = False
        t = (df["time"] / 1000).to_numpy()
        width = (t[1] - t[0]) * 0.65 if len(t) > 1 else 1
        self.candles.set_data(t, df["open"], df["high"], df["low"], df["close"], width)
        self.ema_f.setData(t, df["ema_fast"].to_numpy())
        self.ema_s.setData(t, df["ema_slow"].to_numpy())
        self.rsi_line.setData(t, df["rsi"].to_numpy())
        self.marks.setData(self.trade_marks)
        self.vb.setLimits(xMin=t[0] - width * 20, xMax=t[-1] + width * 200)
        if self._first:
            self._first = False
            self._show_last(150)
        elif self.follow and not grew:
            self._show_last(span)
        else:
            self.fit_y()

    def add_trade_mark(self, ts_ms: int, price: float, side: str, is_exit: bool = False):
        up = (side == "long") != is_exit
        self.trade_marks.append({"pos": (ts_ms / 1000, price), "symbol": "t1" if up else "t",
                                 "brush": pg.mkBrush(TEAL if up else RED), "pen": None})
        self.marks.setData(self.trade_marks)

    # -------- position overlay --------
    def show_position(self, pos):
        for ln in self.pos_lines:
            self.price.removeItem(ln)
        self.pos_lines.clear()
        if pos is None:
            return

        def line(y, color, label, dash=False):
            ln = pg.InfiniteLine(y, angle=0, movable=False,
                                 pen=pg.mkPen(color, width=1.6, style=Qt.DashLine if dash else Qt.SolidLine),
                                 label=f"{label}  {y:,.6g}", labelOpts={"position": 0.04, "color": color, "fill": (255, 255, 255, 220)})
            self.price.addItem(ln, ignoreBounds=True)
            self.pos_lines.append(ln)
        line(pos.entry, NAVY, "ورود " + ("لانگ" if pos.side == "long" else "شورت"))
        line(pos.take_profit, TEAL, "حد سود")
        line(pos.stop_loss, RED, "حد ضرر")
        if pos.liq_price:
            line(pos.liq_price, EMBER, "لیکوئید", dash=True)
