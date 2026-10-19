"""Internal test (paper) account with leverage, isolated margin and liquidation."""
from __future__ import annotations

import itertools
import time
from dataclasses import dataclass, field

from didban.config import RiskSettings

_ids = itertools.count(1)


@dataclass
class Position:
    symbol: str
    side: str
    qty: float
    entry: float
    leverage: int
    margin: float
    stop_loss: float
    take_profit: float
    liq_price: float | None
    opened_at: float = field(default_factory=time.time)
    id: int = field(default_factory=lambda: next(_ids))
    mark: float = 0.0

    @property
    def pnl(self) -> float:
        d = (self.mark - self.entry) if self.side == "long" else (self.entry - self.mark)
        return d * self.qty

    @property
    def roe(self) -> float:
        return self.pnl / self.margin if self.margin else 0.0


@dataclass
class ClosedTrade:
    symbol: str
    side: str
    entry: float
    exit: float
    qty: float
    leverage: int
    pnl: float
    reason: str
    closed_at: float = field(default_factory=time.time)


class PaperAccount:
    def __init__(self, balance: float = 10_000.0, market_type: str = "futures", leverage: int = 5,
                 risk: RiskSettings | None = None):
        self.start_balance = balance
        self.balance = balance          # wallet balance (realized)
        self.market_type = market_type
        self.leverage = leverage if market_type != "spot" else 1
        self.risk = risk or RiskSettings()
        self.positions: dict[str, Position] = {}
        self.history: list[ClosedTrade] = []
        self.peak_equity = balance

    # ------------- account values -------------
    @property
    def used_margin(self) -> float:
        return sum(p.margin for p in self.positions.values())

    @property
    def unrealized(self) -> float:
        return sum(p.pnl for p in self.positions.values())

    @property
    def equity(self) -> float:
        return self.balance + self.unrealized

    @property
    def available(self) -> float:
        return self.balance - self.used_margin

    @property
    def drawdown(self) -> float:
        self.peak_equity = max(self.peak_equity, self.equity)
        return 1 - self.equity / self.peak_equity if self.peak_equity else 0

    def reset(self, balance: float | None = None):
        self.__init__(balance or self.start_balance, self.market_type, self.leverage, self.risk)

    # ------------- trading -------------
    def liquidation_price(self, side: str, entry: float, lev: int) -> float | None:
        if lev <= 1:
            return None
        mmr = self.risk.maintenance_margin
        return entry * (1 - 1 / lev + mmr) if side == "long" else entry * (1 + 1 / lev - mmr)

    def size_for(self, entry: float, stop: float) -> float:
        """Quantity so that hitting the stop loses `risk_per_trade` of equity."""
        risk_cash = self.equity * self.risk.risk_per_trade
        dist = abs(entry - stop)
        if dist <= 0:
            return 0.0
        qty = risk_cash / dist
        max_qty = self.available * self.leverage / entry * 0.98
        return max(0.0, min(qty, max_qty))

    def open(self, symbol: str, side: str, entry: float, sl: float, tp: float, qty: float | None = None) -> Position | None:
        if symbol in self.positions or len(self.positions) >= self.risk.max_open_positions:
            return None
        if side == "short" and self.market_type == "spot":
            return None
        qty = qty if qty is not None else self.size_for(entry, sl)
        if qty <= 0:
            return None
        margin = qty * entry / self.leverage
        fee = qty * entry * self.risk.fee_rate
        if margin + fee > self.available:
            return None
        self.balance -= fee
        liq = self.liquidation_price(side, entry, self.leverage)
        pos = Position(symbol, side, qty, entry, self.leverage, margin, sl, tp, liq, mark=entry)
        self.positions[symbol] = pos
        return pos

    def close(self, symbol: str, price: float, reason: str = "دستی") -> ClosedTrade | None:
        pos = self.positions.pop(symbol, None)
        if not pos:
            return None
        pos.mark = price
        pnl = pos.pnl - pos.qty * price * self.risk.fee_rate
        if reason == "لیکوئید":
            pnl = -pos.margin
        self.balance += pnl
        t = ClosedTrade(symbol, pos.side, pos.entry, price, pos.qty, pos.leverage, pnl, reason)
        self.history.append(t)
        return t

    def on_candle(self, symbol: str, high: float, low: float, close: float) -> ClosedTrade | None:
        """Update mark price and trigger liquidation / SL / TP (worst case first)."""
        pos = self.positions.get(symbol)
        if not pos:
            return None
        pos.mark = close
        if pos.side == "long":
            if pos.liq_price and low <= pos.liq_price and pos.liq_price >= pos.stop_loss:
                return self.close(symbol, pos.liq_price, "لیکوئید")
            if low <= pos.stop_loss:
                return self.close(symbol, pos.stop_loss, "حد ضرر")
            if high >= pos.take_profit:
                return self.close(symbol, pos.take_profit, "حد سود")
        else:
            if pos.liq_price and high >= pos.liq_price and pos.liq_price <= pos.stop_loss:
                return self.close(symbol, pos.liq_price, "لیکوئید")
            if high >= pos.stop_loss:
                return self.close(symbol, pos.stop_loss, "حد ضرر")
            if low <= pos.take_profit:
                return self.close(symbol, pos.take_profit, "حد سود")
        return None

    def stats(self) -> dict:
        wins = [t for t in self.history if t.pnl > 0]
        return {
            "trades": len(self.history),
            "win_rate": len(wins) / len(self.history) if self.history else 0.0,
            "pnl": self.equity - self.start_balance,
            "drawdown": self.drawdown,
        }
