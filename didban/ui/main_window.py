"""Main window: dockable (movable / resizable / floatable) charts and panels."""
from __future__ import annotations

import time

from PySide6.QtGui import QColor
from PySide6.QtCore import QRunnable, QSettings, Qt, QThread, QThreadPool, Signal
from PySide6.QtCore import QRegularExpression, QStringListModel
from PySide6.QtGui import QRegularExpressionValidator
from PySide6.QtWidgets import (QAbstractItemView, QComboBox, QCompleter, QDialog, QDockWidget, QDoubleSpinBox,
    QFormLayout, QHBoxLayout, QHeaderView, QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow, QMenu,
    QMessageBox, QPushButton, QSpinBox, QTableWidget, QTableWidgetItem, QTextEdit, QToolBar, QVBoxLayout, QWidget)

from didban import storage
from didban.config import ACCOUNT_MODES, APP_VERSION, DEFAULT_SYMBOLS, MARKET_TYPES, TIMEFRAMES, TradingStyle, tf_seconds
from didban.core import analyzers
from didban.ui.dialogs import AiDialog, ApiDialog, SymbolPicker, exchange_keys
from didban.data.exchange import ExchangeClient, available_exchanges
from didban.trading.bot import MarketWorker
from didban.trading.paper import PaperAccount
from didban.ui.chart import ChartView
from didban.ui.theme import EMBER, MUTED, RED, TEAL


class _Task(QRunnable):
    """Run a blocking exchange call off the UI thread."""
    def __init__(self, fn, done):
        super().__init__()
        self.fn, self.done = fn, done

    def run(self):
        try:
            self.done.emit(self.fn(), None)
        except Exception as e:  # noqa: BLE001
            self.done.emit(None, str(e))


class MainWindow(QMainWindow):
    sig_symbols = Signal(object)
    sig_timeframe = Signal(str)
    sig_analyzer = Signal(object)
    sig_more = Signal(str, int)
    order_done = Signal(object, object)

    def __init__(self, style: TradingStyle):
        super().__init__()
        self.style = style
        self.setWindowTitle(f"دیدبان {APP_VERSION} · {style.title_fa}")
        self.resize(1440, 880)
        self.setDockOptions(QMainWindow.AllowNestedDocks | QMainWindow.AllowTabbedDocks | QMainWindow.AnimatedDocks)
        self.setDockNestingEnabled(True)

        self.client: ExchangeClient | None = None
        self.thread: QThread | None = None
        self.worker: MarketWorker | None = None
        self.account = PaperAccount(10_000, "futures", 5)
        self.charts: dict[str, tuple[QDockWidget, ChartView]] = {}
        self.last: dict[str, tuple] = {}            # symbol -> (df, signal)
        self.traded_candle: dict[str, int] = {}    # avoid re-entering on the same candle
        self.active_symbol = DEFAULT_SYMBOLS[0]
        self.all_symbols: list[str] = []
        self.watchlist: list[str] = storage.get("watchlist", DEFAULT_SYMBOLS)
        self.bot_checked: set[str] = set(storage.get("bot_symbols", DEFAULT_SYMBOLS[:3]))
        self.bot_running = False
        self.order_done.connect(self._on_order_done)

        self._build_toolbar()
        self._build_watchlist()
        self._build_signal_panel()
        self._build_bot_panel()
        self._build_positions()
        self._build_log()
        self.settings = QSettings("Didban", "DidbanTrader")
        self.connect_exchange()
        self.open_chart(self.active_symbol)
        state = self.settings.value(f"layout/{style.key}")
        if state:
            self.restoreState(state)

    # ======================= UI building =======================
    def _dock(self, title, widget, area, name):
        d = QDockWidget(title, self)
        d.setObjectName(name)
        d.setWidget(widget)
        d.setFeatures(QDockWidget.DockWidgetMovable | QDockWidget.DockWidgetFloatable | QDockWidget.DockWidgetClosable)
        self.addDockWidget(area, d)
        return d

    def _build_toolbar(self):
        tb = QToolBar("main", self)
        tb.setObjectName("toolbar")
        tb.setMovable(False)
        self.addToolBar(tb)
        tb.addWidget(QLabel(f"  دیدبان · {self.style.title_fa}  "))
        self.cb_exchange = QComboBox()
        self.cb_exchange.addItems(available_exchanges())
        last_ex = storage.get("exchange")
        if last_ex and self.cb_exchange.findText(last_ex) >= 0:
            self.cb_exchange.setCurrentText(last_ex)
        self.cb_market = QComboBox()
        for k, (fa, _) in MARKET_TYPES.items():
            self.cb_market.addItem(fa, k)
        self.cb_market.setCurrentIndex(2)
        self.sp_lev = QSpinBox()
        self.sp_lev.setRange(1, 125)
        self.sp_lev.setValue(5)
        self.sp_lev.setSuffix("x")
        self.cb_account = QComboBox()
        for k, fa in ACCOUNT_MODES.items():
            self.cb_account.addItem(fa, k)
        # timeframe: presets + any custom value (e.g. 7m, 45m, 3h, 10h, 2d)
        self.cb_tf = QComboBox()
        self.cb_tf.setEditable(True)
        self.cb_tf.setInsertPolicy(QComboBox.NoInsert)
        custom = storage.get("custom_timeframes", [])
        self.cb_tf.addItems(TIMEFRAMES + [t for t in custom if t not in TIMEFRAMES])
        self.cb_tf.lineEdit().setValidator(QRegularExpressionValidator(QRegularExpression(r"^[1-9][0-9]{0,2}[mhdwM]$")))
        self.cb_tf.setToolTip("از لیست انتخاب کن یا تایپ کن، مثلاً 7m، 45m، 3h، 2d و Enter بزن")
        self.cb_tf.setCurrentText(self.style.timeframe)
        self.cb_tf.setMinimumWidth(80)
        self.current_tf = self.style.timeframe
        # AI engine
        self.cb_ai = QComboBox()
        for k, e in analyzers.ENGINES.items():
            self.cb_ai.addItem(e.title, k)
        self.cb_ai.setCurrentIndex(max(0, self.cb_ai.findData(storage.get("ai_engine", "hgb"))))
        btn_ai = QPushButton("⚙")
        btn_ai.setToolTip("تنظیمات هوش مصنوعی و کلید API")
        btn_api = QPushButton("🔑 API صرافی")
        btn_connect = QPushButton("اتصال")
        btn_chart = QPushButton("چارت جدید")
        for lbl, w in (("صرافی", self.cb_exchange), ("بازار", self.cb_market), ("اهرم", self.sp_lev),
                       ("حساب", self.cb_account), ("تایم‌فریم", self.cb_tf), ("هوش مصنوعی", self.cb_ai)):
            tb.addWidget(QLabel(lbl))
            tb.addWidget(w)
        tb.addWidget(btn_ai)
        tb.addWidget(btn_api)
        tb.addWidget(btn_connect)
        tb.addWidget(btn_chart)
        btn_connect.clicked.connect(self.connect_exchange)
        btn_api.clicked.connect(self.open_api_dialog)
        btn_ai.clicked.connect(self.open_ai_dialog)
        btn_chart.clicked.connect(lambda: self.open_chart(self.active_symbol, force_new=True))
        self.cb_market.currentIndexChanged.connect(self._market_changed)
        self.sp_lev.valueChanged.connect(self._lev_changed)
        self.cb_tf.activated.connect(lambda _: self._tf_changed(self.cb_tf.currentText()))
        self.cb_tf.lineEdit().returnPressed.connect(lambda: self._tf_changed(self.cb_tf.currentText()))
        self.cb_ai.activated.connect(lambda _: self._ai_changed())

    def _build_watchlist(self):
        w = QWidget()
        v = QVBoxLayout(w)
        self.search = QLineEdit(placeholderText="جستجو یا افزودن نماد... (مثلاً PEPE و Enter)")
        self.completer = QCompleter([], self)
        self.completer.setCaseSensitivity(Qt.CaseInsensitive)
        self.completer.setFilterMode(Qt.MatchContains)
        self.search.setCompleter(self.completer)
        self.lst = QListWidget()
        self.lst.setSelectionMode(QAbstractItemView.SingleSelection)
        self.lst.setContextMenuPolicy(Qt.CustomContextMenu)
        row = QHBoxLayout()
        btn_add = QPushButton("+ افزودن ارز")
        btn_all = QPushButton("همه", objectName="ghost")
        btn_none = QPushButton("هیچ", objectName="ghost")
        btn_all.setToolTip("ربات روی همه ارزهای واچ‌لیست")
        row.addWidget(btn_add, 1)
        row.addWidget(btn_all)
        row.addWidget(btn_none)
        hint = QLabel("تیک = ربات روی این ارز کار کند · دوبار کلیک = چارت · راست‌کلیک = حذف")
        hint.setObjectName("muted")
        hint.setWordWrap(True)
        v.addWidget(self.search)
        v.addLayout(row)
        v.addWidget(self.lst)
        v.addWidget(hint)
        self.search.textChanged.connect(self._filter_list)
        self.search.returnPressed.connect(self._add_from_search)
        self.completer.activated.connect(lambda t: self._add_symbols([t]))
        btn_add.clicked.connect(self._pick_symbols)
        btn_all.clicked.connect(lambda: self._check_all(True))
        btn_none.clicked.connect(lambda: self._check_all(False))
        self.lst.itemChanged.connect(self._item_checked)
        self.lst.itemClicked.connect(lambda it: self._set_active(it.text()))
        self.lst.itemDoubleClicked.connect(lambda it: self.open_chart(it.text(), force_new=True))
        self.lst.customContextMenuRequested.connect(self._list_menu)
        self._dock("دیده‌بانی بازار", w, Qt.RightDockWidgetArea, "watchlist")

    def _build_signal_panel(self):
        w = QWidget()
        v = QVBoxLayout(w)
        self.lb_sig_sym = QLabel("-")
        self.lb_sig_sym.setObjectName("muted")
        self.lb_sig = QLabel("در انتظار داده...")
        self.lb_sig.setObjectName("big")
        self.lb_conf = QLabel("")
        self.lb_levels = QLabel("")
        self.lb_reasons = QLabel("")
        self.lb_reasons.setWordWrap(True)
        self.lb_reasons.setObjectName("muted")
        for x in (self.lb_sig_sym, self.lb_sig, self.lb_conf, self.lb_levels, self.lb_reasons):
            v.addWidget(x)
        h = QHBoxLayout()
        self.btn_buy = QPushButton("خرید / لانگ", objectName="buy")
        self.btn_sell = QPushButton("فروش / شورت", objectName="sell")
        self.btn_buy.clicked.connect(lambda: self.manual_trade("long"))
        self.btn_sell.clicked.connect(lambda: self.manual_trade("short"))
        h.addWidget(self.btn_buy)
        h.addWidget(self.btn_sell)
        v.addLayout(h)
        v.addStretch()
        self._dock("سیگنال هوش مصنوعی", w, Qt.LeftDockWidgetArea, "signal")

    def _build_bot_panel(self):
        w = QWidget()
        f = QFormLayout(w)
        self.btn_bot = QPushButton("▶ شروع ربات")
        self.btn_bot.clicked.connect(self.toggle_bot)
        self.sp_risk = QDoubleSpinBox(decimals=2, minimum=0.1, maximum=10, value=1.0, suffix=" %")
        self.sp_maxpos = QSpinBox(minimum=1, maximum=20, value=3)
        self.sp_dd = QDoubleSpinBox(decimals=1, minimum=1, maximum=90, value=10, suffix=" %")
        self.sp_balance = QDoubleSpinBox(decimals=0, minimum=10, maximum=10_000_000, value=10_000, suffix=" USDT")
        self.cb_margin_mode = QComboBox()
        self.cb_margin_mode.addItem("ایزوله", "isolated")
        self.cb_margin_mode.addItem("کراس", "cross")
        btn_reset = QPushButton("ریست حساب تست", objectName="ghost")
        btn_reset.clicked.connect(self.reset_paper)
        self.lb_equity = QLabel()
        self.lb_equity.setObjectName("big")
        self.lb_stats = QLabel()
        self.lb_stats.setObjectName("muted")
        f.addRow(self.btn_bot)
        f.addRow("ریسک هر معامله", self.sp_risk)
        f.addRow("حداکثر پوزیشن باز", self.sp_maxpos)
        f.addRow("توقف در افت", self.sp_dd)
        f.addRow("نوع مارجین", self.cb_margin_mode)
        f.addRow("موجودی حساب تست", self.sp_balance)
        f.addRow(btn_reset)
        f.addRow("ارزش حساب", self.lb_equity)
        f.addRow(self.lb_stats)
        for sp in (self.sp_risk, self.sp_maxpos, self.sp_dd):
            sp.valueChanged.connect(self._risk_changed)
        self._dock("ربات و حساب", w, Qt.LeftDockWidgetArea, "bot")
        self._refresh_account()

    def _build_positions(self):
        self.tbl = QTableWidget(0, 9)
        self.tbl.setHorizontalHeaderLabels(["نماد", "جهت", "اهرم", "حجم", "ورود", "حد ضرر", "حد سود", "سود/زیان", ""])
        self.tbl.horizontalHeader().setSectionResizeMode(QHeaderView.Stretch)
        self.tbl.verticalHeader().hide()
        self.tbl.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self._dock("پوزیشن‌های باز", self.tbl, Qt.BottomDockWidgetArea, "positions")

    def _build_log(self):
        self.log_box = QTextEdit(readOnly=True)
        self._dock("گزارش", self.log_box, Qt.BottomDockWidgetArea, "log")

    # ======================= connection =======================
    def open_api_dialog(self):
        dlg = ApiDialog(self, self.cb_exchange.currentText(), self.cb_market.currentData())
        if dlg.exec() == QDialog.Accepted:
            name, *_ = dlg.values()
            self.cb_exchange.setCurrentText(name)
            self._pending_keys = dlg.values()
            self.connect_exchange()

    def _make_analyzer(self):
        key = self.cb_ai.currentData()
        e = analyzers.ENGINES[key]
        api = storage.get_secret(f"ai:{key}") if e.kind == "llm" else ""
        if e.kind == "llm" and e.needs_key and not api:
            self.log(f"برای {e.title} کلید API لازم است. روی ⚙ بزن.", EMBER)
            return None
        return analyzers.create(key, self.style.horizon, api, storage.get(f"ai_model:{key}", ""),
                                storage.get(f"ai_base:{key}", ""))

    def open_ai_dialog(self):
        dlg = AiDialog(self, self.cb_ai.currentData())
        if dlg.exec() == QDialog.Accepted:
            self.cb_ai.setCurrentIndex(max(0, self.cb_ai.findData(storage.get("ai_engine"))))
            self._ai_changed()

    def _ai_changed(self):
        storage.put("ai_engine", self.cb_ai.currentData())
        an = self._make_analyzer()
        if self.worker:
            self.worker.engine.ai_weight = float(storage.get("ai_weight", 55)) / 100
            self.sig_analyzer.emit(an)
        self.log(f"موتور تحلیل: {self.cb_ai.currentText()}", TEAL)

    def connect_exchange(self):
        name = self.cb_exchange.currentText()
        mtype = self.cb_market.currentData()
        acc = self.cb_account.currentData()
        pending = getattr(self, "_pending_keys", None)
        self._pending_keys = None
        if pending and pending[0] == name:
            _, key, secret, pw, testnet = pending
        else:
            key, secret, pw = exchange_keys(name)
            testnet = bool(storage.get(f"testnet:{name}", False))
        demo = name.startswith("Demo")
        if acc == "testnet":
            testnet = True
        if acc in ("testnet", "live") and not demo and not key:
            QMessageBox.information(self, "کلید API", f"برای {ACCOUNT_MODES[acc]} اول کلید API {name} را وارد کن.")
            self.open_api_dialog()
            return
        if acc == "live":
            ok = QMessageBox.warning(self, "حساب واقعی", "ربات با پول واقعی معامله می‌کند. مطمئنی؟",
                                     QMessageBox.Yes | QMessageBox.No, QMessageBox.No)
            if ok != QMessageBox.Yes:
                return
        try:
            client = ExchangeClient(name, mtype, key, secret, pw, testnet=(testnet and acc != "paper"))
            symbols = client.symbols()
        except Exception as e:  # noqa: BLE001
            QMessageBox.critical(self, "خطای اتصال", str(e))
            return
        self._stop_worker()
        self.client = client
        storage.put("exchange", name)
        self.account.market_type = mtype
        self.account.leverage = 1 if mtype == "spot" else self.sp_lev.value()
        self.all_symbols = symbols
        self.completer.setModel(QStringListModel(symbols, self.completer))
        self._fill_watchlist()
        self.worker = MarketWorker(client, self.style, mtype != "spot", self.current_tf, self._make_analyzer())
        self.worker.engine.ai_weight = float(storage.get("ai_weight", 55)) / 100
        self.thread = QThread(self)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.start)
        self.worker.data_ready.connect(self.on_data)
        self.worker.error.connect(lambda m: self.log(m, EMBER))
        self.worker.info.connect(lambda m: self.log(m))
        self.sig_symbols.connect(self.worker.set_symbols)
        self.sig_timeframe.connect(self.worker.set_timeframe)
        self.sig_analyzer.connect(self.worker.set_analyzer)
        self.sig_more.connect(self.worker.load_more)
        self.thread.start()
        self._push_symbols()
        keyinfo = "با کلید API" if client.has_keys else "بدون کلید (فقط داده)"
        self.log(f"متصل شد: {name} · {MARKET_TYPES[mtype][0]} · {ACCOUNT_MODES[acc]} · {keyinfo} · {len(symbols)} بازار", TEAL)
        if client.has_keys and not demo:
            from didban.ui.dialogs import run_async
            self._bal = run_async(client.test, lambda m, e: self.log(m if not e else f"خطای کلید API: {e}", TEAL if not e else RED))

    def _stop_worker(self):
        if self.thread:
            try:
                self.sig_symbols.disconnect()
                self.sig_timeframe.disconnect()
                self.sig_analyzer.disconnect()
                self.sig_more.disconnect()
            except (RuntimeError, TypeError):
                pass
            self.thread.quit()
            self.thread.wait(3000)
        self.thread = self.worker = None

    def _fill_watchlist(self):
        avail = set(self.all_symbols)
        wl = [x for x in self.watchlist if x in avail]
        if not wl:  # exchange uses different names (e.g. futures "BTC/USDT:USDT")
            wl = [x for x in self.all_symbols if x.split(":")[0] in set(self.watchlist)] or self.all_symbols[:30]
        self.lst.blockSignals(True)
        self.lst.clear()
        for x in wl:
            self._list_add_item(x)
        self.lst.blockSignals(False)
        if wl and self.active_symbol not in wl:
            self.active_symbol = wl[0]

    def _list_add_item(self, sym):
        it = QListWidgetItem(sym)
        it.setFlags(it.flags() | Qt.ItemIsUserCheckable)
        it.setCheckState(Qt.Checked if sym in self.bot_checked or sym.split(":")[0] in self.bot_checked else Qt.Unchecked)
        self.lst.addItem(it)

    def _watched(self) -> list[str]:
        return [self.lst.item(i).text() for i in range(self.lst.count())]

    def _save_watchlist(self):
        self.watchlist = [x.split(":")[0] for x in self._watched()]
        storage.put("watchlist", self.watchlist)
        storage.put("bot_symbols", [x.split(":")[0] for x in self.bot_symbols()])

    def _add_symbols(self, syms):
        have = set(self._watched())
        added = [x for x in syms if x in set(self.all_symbols) and x not in have]
        self.lst.blockSignals(True)
        for x in added:
            self._list_add_item(x)
        self.lst.blockSignals(False)
        if added:
            self._save_watchlist()
            self.log("اضافه شد: " + "، ".join(added), TEAL)
        self.search.clear()

    def _add_from_search(self):
        t = self.search.text().upper().strip()
        if not t:
            return
        exact = [x for x in self.all_symbols if x.split(":")[0] in (t, f"{t}/USDT")]
        if exact:
            self._add_symbols(exact[:1])
            self.open_chart(exact[0])
        else:
            self.log(f"نماد {t} در این صرافی پیدا نشد.", EMBER)

    def _pick_symbols(self):
        if not self.all_symbols:
            return
        dlg = SymbolPicker(self, self.all_symbols, self._watched())
        if dlg.exec() == QDialog.Accepted:
            chosen = dlg.selected()
            keep = set(chosen)
            self.lst.blockSignals(True)
            for i in reversed(range(self.lst.count())):
                if self.lst.item(i).text() not in keep:
                    self.lst.takeItem(i)
            self.lst.blockSignals(False)
            self._add_symbols(chosen)
            self._save_watchlist()
            self._push_symbols()

    def _list_menu(self, pos):
        it = self.lst.itemAt(pos)
        if not it:
            return
        m = QMenu(self)
        a_chart = m.addAction("باز کردن چارت")
        a_del = m.addAction("حذف از واچ‌لیست")
        act = m.exec(self.lst.mapToGlobal(pos))
        if act == a_chart:
            self.open_chart(it.text(), force_new=True)
        elif act == a_del:
            self.lst.takeItem(self.lst.row(it))
            self._save_watchlist()
            self._push_symbols()

    def _check_all(self, on):
        self.lst.blockSignals(True)
        for i in range(self.lst.count()):
            self.lst.item(i).setCheckState(Qt.Checked if on else Qt.Unchecked)
        self.lst.blockSignals(False)
        self._item_checked()

    def _item_checked(self, *_):
        self.bot_checked = {x.split(":")[0] for x in self.bot_symbols()}
        self._save_watchlist()
        self._push_symbols()

    def _filter_list(self, text):
        t = text.upper().strip()
        for i in range(self.lst.count()):
            it = self.lst.item(i)
            it.setHidden(bool(t) and t not in it.text())

    def bot_symbols(self) -> list[str]:
        return [self.lst.item(i).text() for i in range(self.lst.count()) if self.lst.item(i).checkState() == Qt.Checked]

    def _push_symbols(self):
        watched = set(self.bot_symbols()) | set(self.charts) | set(self.account.positions)
        self.sig_symbols.emit(watched)

    # ======================= charts =======================
    def open_chart(self, symbol: str, force_new=False):
        self._set_active(symbol)
        if symbol in self.charts:
            self.charts[symbol][0].raise_()
            return
        view = ChartView(symbol)
        view.need_history.connect(lambda sym, n: self.sig_more.emit(sym, n))
        dock = self._dock(f"چارت {symbol}", view, Qt.TopDockWidgetArea, f"chart_{symbol}")
        dock.setMinimumSize(360, 260)
        others = [d for d, _ in self.charts.values()]
        if others and not force_new:
            self.tabifyDockWidget(others[-1], dock)
        dock.setAttribute(Qt.WA_DeleteOnClose)
        dock.visibilityChanged.connect(lambda vis, s=symbol: vis and self._set_active(s))
        dock.destroyed.connect(lambda *_, s=symbol: self._close_chart(s))
        self.charts[symbol] = (dock, view)
        if symbol in self.last:
            view.update_data(self.last[symbol][0])
        view.show_position(self.account.positions.get(symbol))
        self._push_symbols()

    def _close_chart(self, symbol):
        self.charts.pop(symbol, None)
        self._push_symbols()

    def _set_active(self, symbol):
        self.active_symbol = symbol
        if symbol in self.last:
            self._show_signal(self.last[symbol][1])

    # ======================= data loop =======================
    def on_data(self, symbol, df, sig):
        self.last[symbol] = (df, sig)
        last = df.iloc[-1]
        closed = self.account.on_candle(symbol, float(last["high"]), float(last["low"]), float(last["close"]))
        if closed:
            color = TEAL if closed.pnl > 0 else RED
            self.log(f"بسته شد {symbol} ({closed.reason}) · سود/زیان {closed.pnl:+.2f} USDT", color)
            if symbol in self.charts:
                self.charts[symbol][1].add_trade_mark(int(last["time"]), closed.exit, closed.side, is_exit=True)
        if symbol in self.charts:
            view = self.charts[symbol][1]
            view.update_data(df)
            view.show_position(self.account.positions.get(symbol))
        if symbol == self.active_symbol:
            self._show_signal(sig)
        if self.bot_running:
            self._bot_step(symbol, sig)
        self._refresh_account()

    def _bot_step(self, symbol, sig):
        if self.account.drawdown >= self.sp_dd.value() / 100:
            self.toggle_bot()
            self.log("ربات به دلیل رسیدن به حداکثر افت متوقف شد.", RED)
            return
        if symbol not in self.bot_symbols() or symbol in self.account.positions:
            return
        if not sig.actionable or sig.confidence < self.style.min_confidence:
            return
        if self.traded_candle.get(symbol) == sig.time:
            return
        self.traded_candle[symbol] = sig.time
        self._open(symbol, sig.side, sig)

    def manual_trade(self, side):
        sym = self.active_symbol
        if sym not in self.last:
            return
        df, sig = self.last[sym]
        price, a = sig.entry, float(df.iloc[-1]["atr"])
        if side == "long":
            sl, tp = price - a * self.style.sl_atr, price + a * self.style.tp_atr
        else:
            sl, tp = price + a * self.style.sl_atr, price - a * self.style.tp_atr
        from didban.core.signals import Signal as Sig
        self._open(sym, side, Sig(sym, side, 1.0, price, sl, tp, None))

    def _open(self, symbol, side, sig):
        pos = self.account.open(symbol, side, sig.entry, sig.stop_loss, sig.take_profit)
        if not pos:
            self.log(f"پوزیشن {symbol} باز نشد (موجودی ناکافی، محدودیت تعداد یا شورت در اسپات).", EMBER)
            return
        side_fa = "لانگ" if side == "long" else "شورت"
        self.log(f"باز شد {side_fa} {symbol} @ {pos.entry:,.4f} · اهرم {pos.leverage}x · SL {pos.stop_loss:,.4f} · TP {pos.take_profit:,.4f}", TEAL)
        if symbol in self.charts:
            v = self.charts[symbol][1]
            v.show_position(pos)
            v.add_trade_mark(sig.time or int(time.time() * 1000), pos.entry, side)
        acc = self.cb_account.currentData()
        if acc in ("testnet", "live") and self.client and not self.client.demo:
            c, lev, mm = self.client, pos.leverage, self.cb_margin_mode.currentData()
            def send():
                c.prepare_leverage(symbol, lev, mm)
                return c.open_position(symbol, side, pos.qty, pos.stop_loss, pos.take_profit)
            QThreadPool.globalInstance().start(_Task(send, self.order_done))
        self._push_symbols()
        self._refresh_account()

    def _on_order_done(self, result, err):
        if err:
            self.log(f"خطای سفارش صرافی: {err}", RED)
        else:
            self.log(f"سفارش صرافی ثبت شد: {result.get('id') if isinstance(result, dict) else result}", TEAL)

    def close_position(self, symbol):
        pos = self.account.positions.get(symbol)
        if not pos:
            return
        t = self.account.close(symbol, pos.mark, "دستی")
        self.log(f"بسته شد {symbol} دستی · {t.pnl:+.2f} USDT", TEAL if t.pnl > 0 else RED)
        if self.cb_account.currentData() in ("testnet", "live") and self.client and not self.client.demo:
            c = self.client
            QThreadPool.globalInstance().start(_Task(lambda: c.close_position(symbol, pos.side, pos.qty), self.order_done))
        if symbol in self.charts:
            self.charts[symbol][1].show_position(None)
        self._refresh_account()

    # ======================= panels =======================
    def _show_signal(self, sig):
        self.lb_sig_sym.setText(f"{sig.symbol} · {self.current_tf}")
        text = {"long": "ورود (خرید / لانگ)", "short": "ورود (فروش / شورت)", "flat": "صبر کن"}[sig.side]
        color = {"long": TEAL, "short": RED, "flat": MUTED}[sig.side]
        self.lb_sig.setText(text)
        self.lb_sig.setStyleSheet(f"color: {color};")
        self.lb_conf.setText(f"اطمینان: {sig.confidence*100:.0f}%")
        if sig.actionable:
            self.lb_levels.setText(f"ورود {sig.entry:,.4f}\nحد ضرر {sig.stop_loss:,.4f}\nحد سود {sig.take_profit:,.4f}")
        else:
            self.lb_levels.setText(f"قیمت {sig.entry:,.4f}")
        self.lb_reasons.setText("• " + "\n• ".join(sig.reasons))

    def _refresh_account(self):
        a = self.account
        self.lb_equity.setText(f"{a.equity:,.2f} $")
        s = a.stats()
        self.lb_stats.setText(f"آزاد {a.available:,.0f} · مارجین {a.used_margin:,.0f} · معاملات {s['trades']} · "
                              f"وین‌ریت {s['win_rate']*100:.0f}% · افت {s['drawdown']*100:.1f}%")
        self.tbl.setRowCount(len(a.positions))
        for r, p in enumerate(a.positions.values()):
            vals = [p.symbol, "لانگ" if p.side == "long" else "شورت", f"{p.leverage}x", f"{p.qty:.4f}",
                    f"{p.entry:,.4f}", f"{p.stop_loss:,.4f}", f"{p.take_profit:,.4f}", f"{p.pnl:+.2f} ({p.roe*100:+.1f}%)"]
            for c, v in enumerate(vals):
                it = QTableWidgetItem(v)
                if c == 7:
                    it.setForeground(QColor(TEAL if p.pnl >= 0 else RED))
                self.tbl.setItem(r, c, it)
            btn = QPushButton("بستن", objectName="sell")
            btn.clicked.connect(lambda _=False, s=p.symbol: self.close_position(s))
            self.tbl.setCellWidget(r, 8, btn)

    def toggle_bot(self):
        self.bot_running = not self.bot_running
        self.btn_bot.setText("■ توقف ربات" if self.bot_running else "▶ شروع ربات")
        self.btn_bot.setObjectName("sell" if self.bot_running else "")
        self.btn_bot.style().unpolish(self.btn_bot)
        self.btn_bot.style().polish(self.btn_bot)
        self.log(("ربات روشن شد روی: " + "، ".join(self.bot_symbols())) if self.bot_running else "ربات متوقف شد.")

    def reset_paper(self):
        if self.account.positions:
            QMessageBox.information(self, "ریست", "اول پوزیشن‌های باز را ببند.")
            return
        self.account.reset(self.sp_balance.value())
        self._refresh_account()
        self.log(f"حساب تست با {self.sp_balance.value():,.0f} USDT ریست شد.")

    def _risk_changed(self):
        r = self.account.risk
        r.risk_per_trade = self.sp_risk.value() / 100
        r.max_open_positions = self.sp_maxpos.value()
        r.max_drawdown = self.sp_dd.value() / 100

    def _market_changed(self):
        mtype = self.cb_market.currentData()
        self.sp_lev.setMaximum(MARKET_TYPES[mtype][1])
        self.sp_lev.setEnabled(mtype != "spot")
        self.log("نوع بازار عوض شد. برای اعمال، دوباره «اتصال» را بزن.", EMBER)

    def _lev_changed(self, v):
        if self.account.market_type != "spot":
            self.account.leverage = v

    def _tf_changed(self, tf):
        tf = tf.strip()
        if tf == self.current_tf:
            return
        try:
            tf_seconds(tf)
        except ValueError as e:
            self.log(str(e), RED)
            self.cb_tf.setCurrentText(self.current_tf)
            return
        if self.cb_tf.findText(tf) < 0:
            self.cb_tf.addItem(tf)
            custom = storage.get("custom_timeframes", [])
            if tf not in custom:
                storage.put("custom_timeframes", custom + [tf])
        if self.client and not self.client.demo and self.client.native_timeframes() and tf not in self.client.native_timeframes():
            self.log(f"{tf} روی صرافی مستقیم نیست؛ از تایم‌فریم کوچک‌تر ساخته می‌شود.", EMBER)
        self.current_tf = tf
        self.traded_candle.clear()
        for _, view in self.charts.values():
            view.reset_view()
        self.sig_timeframe.emit(tf)
        self.log(f"تایم‌فریم: {tf}")

    def log(self, msg, color=None):
        ts = time.strftime("%H:%M:%S")
        c = color or "#24332f"
        self.log_box.append(f'<span style="color:#5f7470">{ts}</span>  <span style="color:{c}">{msg}</span>')

    def closeEvent(self, e):
        self.settings.setValue(f"layout/{self.style.key}", self.saveState())
        self._stop_worker()
        super().closeEvent(e)
