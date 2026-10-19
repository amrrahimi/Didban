"""Global configuration: trading styles, exchanges, defaults."""
from dataclasses import dataclass, field

APP_NAME = "Didban"
APP_VERSION = "0.3.0"


@dataclass(frozen=True)
class TradingStyle:
    key: str
    title_fa: str
    desc_fa: str
    timeframe: str          # main candle timeframe
    higher_tf: str          # confirmation timeframe
    refresh_sec: int        # how often the bot re-checks the market
    ema_fast: int
    ema_slow: int
    sl_atr: float           # stop loss = ATR * sl_atr
    tp_atr: float           # take profit = ATR * tp_atr
    horizon: int            # candles ahead the AI model predicts
    min_confidence: float   # minimum confidence to open a position


STYLES = {
    "swing": TradingStyle(
        key="swing", title_fa="نوسان‌گیری", desc_fa="نگهداری چند روز تا چند هفته · تایم‌فریم ۴ ساعته",
        timeframe="4h", higher_tf="1d", refresh_sec=60, ema_fast=21, ema_slow=55,
        sl_atr=2.0, tp_atr=4.0, horizon=6, min_confidence=0.62),
    "day": TradingStyle(
        key="day", title_fa="روزانه", desc_fa="باز و بسته شدن معامله در همان روز · تایم‌فریم ۱۵ دقیقه",
        timeframe="15m", higher_tf="1h", refresh_sec=15, ema_fast=9, ema_slow=21,
        sl_atr=1.5, tp_atr=2.5, horizon=8, min_confidence=0.60),
    "long": TradingStyle(
        key="long", title_fa="بلندمدت", desc_fa="سرمایه‌گذاری چند ماهه · تایم‌فریم روزانه",
        timeframe="1d", higher_tf="1w", refresh_sec=300, ema_fast=50, ema_slow=200,
        sl_atr=3.0, tp_atr=8.0, horizon=10, min_confidence=0.65),
}

# Presets shown in the timeframe box. Any custom value like 2m, 7m, 45m, 3h, 10h, 2d is also accepted:
# if the exchange does not support it natively, candles are built (resampled) from a smaller timeframe.
TIMEFRAMES = ["1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "8h", "12h", "1d", "3d", "1w", "1M"]
_UNIT = {"m": 60, "h": 3600, "d": 86400, "w": 604800, "M": 2592000}


def tf_seconds(tf: str) -> int:
    """'15m' -> 900. Raises ValueError for invalid input."""
    tf = tf.strip()
    if len(tf) < 2 or tf[-1] not in _UNIT or not tf[:-1].isdigit() or int(tf[:-1]) <= 0:
        raise ValueError(f"تایم‌فریم نامعتبر: {tf}  (نمونه درست: 7m، 45m، 3h، 2d)")
    return int(tf[:-1]) * _UNIT[tf[-1]]

# Display name -> (ccxt spot id, ccxt futures id). Missing ids are skipped at runtime
# if your installed ccxt version does not support them.
EXCHANGES = {
    "Demo (آفلاین)": (None, None),
    "Binance": ("binance", "binanceusdm"),
    "KuCoin": ("kucoin", "kucoinfutures"),
    "Toobit": ("toobit", "toobit"),
    "Bybit": ("bybit", "bybit"),
    "OKX": ("okx", "okx"),
    "Bitget": ("bitget", "bitget"),
    "Gate.io": ("gate", "gate"),
    "MEXC": ("mexc", "mexc"),
}

MARKET_TYPES = {
    "spot": ("اسپات", 1),          # max leverage
    "margin": ("مارجین", 10),
    "futures": ("فیوچرز", 125),
}

ACCOUNT_MODES = {
    "paper": "حساب تست داخلی (Paper)",
    "testnet": "تست‌نت صرافی",
    "live": "حساب واقعی",
}


@dataclass
class RiskSettings:
    risk_per_trade: float = 0.01   # 1% of equity risked per trade
    max_open_positions: int = 3
    max_drawdown: float = 0.10     # bot stops at 10% drawdown
    fee_rate: float = 0.0006       # taker fee per side (approx)
    maintenance_margin: float = 0.005


# Default watchlist: the most traded coins. Users can search and add any other market.
DEFAULT_SYMBOLS = [
    "BTC/USDT", "ETH/USDT", "BNB/USDT", "SOL/USDT", "XRP/USDT", "DOGE/USDT", "TON/USDT", "ADA/USDT",
    "TRX/USDT", "AVAX/USDT", "SHIB/USDT", "LINK/USDT", "DOT/USDT", "BCH/USDT", "SUI/USDT", "LTC/USDT",
    "NEAR/USDT", "PEPE/USDT", "APT/USDT", "UNI/USDT", "ICP/USDT", "ETC/USDT", "XLM/USDT", "FIL/USDT",
    "ATOM/USDT", "ARB/USDT", "OP/USDT", "INJ/USDT", "HBAR/USDT", "RENDER/USDT", "TAO/USDT", "WIF/USDT",
]
