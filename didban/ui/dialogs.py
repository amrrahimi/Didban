"""Settings dialogs: exchange API keys, AI engine, add symbols."""
from __future__ import annotations

from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, Signal
from PySide6.QtWidgets import (QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFormLayout, QHBoxLayout,
    QLabel, QLineEdit, QListWidget, QListWidgetItem, QPushButton, QVBoxLayout, QAbstractItemView, QDoubleSpinBox)

from didban import storage
from didban.config import EXCHANGES
from didban.core.analyzers import ENGINES
from didban.ui.theme import EMBER, RED, TEAL, MUTED


class _Bridge(QObject):
    done = Signal(object, object)


class _Job(QRunnable):
    def __init__(self, fn, bridge):
        super().__init__()
        self.fn, self.bridge = fn, bridge

    def run(self):
        try:
            self.bridge.done.emit(self.fn(), None)
        except Exception as e:  # noqa: BLE001
            self.bridge.done.emit(None, str(e))


def run_async(fn, callback):
    b = _Bridge()
    b.done.connect(callback)
    job = _Job(fn, b)
    job._bridge = b  # keep alive
    QThreadPool.globalInstance().start(job)
    return b


# ======================= exchange API =======================
def exchange_keys(name: str) -> tuple[str, str, str]:
    return (storage.get_secret(f"{name}:key"), storage.get_secret(f"{name}:secret"), storage.get_secret(f"{name}:password"))


class ApiDialog(QDialog):
    """Enter, test and (optionally) remember API keys for each exchange."""

    def __init__(self, parent, exchange: str, market_type: str):
        super().__init__(parent)
        self.setWindowTitle("اتصال API صرافی")
        self.setMinimumWidth(520)
        self.market_type = market_type
        f = QFormLayout(self)
        self.cb = QComboBox()
        self.cb.addItems([n for n in EXCHANGES if not n.startswith("Demo")])
        if exchange in EXCHANGES and not exchange.startswith("Demo"):
            self.cb.setCurrentText(exchange)
        self.key, self.secret, self.password = QLineEdit(), QLineEdit(), QLineEdit()
        self.secret.setEchoMode(QLineEdit.Password)
        self.password.setEchoMode(QLineEdit.Password)
        self.password.setPlaceholderText("فقط KuCoin / OKX / Bitget")
        self.testnet = QCheckBox("تست‌نت (حساب آزمایشی خود صرافی)")
        self.remember = QCheckBox("ذخیره کلیدها" + (" در Keychain سیستم" if storage.secure_available() else " (نصب keyring برای امنیت بیشتر)"))
        self.remember.setChecked(True)
        self.status = QLabel("")
        self.status.setWordWrap(True)
        btn_test = QPushButton("تست اتصال")
        btn_del = QPushButton("حذف کلیدهای این صرافی", objectName="ghost")
        note = QLabel("فقط دسترسی «Read» و «Trade» بدهید. «Withdraw» را هرگز فعال نکنید. در صورت امکان IP را محدود کنید.")
        note.setWordWrap(True)
        note.setStyleSheet(f"color: {EMBER};")
        f.addRow("صرافی", self.cb)
        f.addRow("API Key", self.key)
        f.addRow("Secret", self.secret)
        f.addRow("Passphrase", self.password)
        f.addRow(self.testnet)
        f.addRow(self.remember)
        row = QHBoxLayout()
        row.addWidget(btn_test)
        row.addWidget(btn_del)
        f.addRow(row)
        f.addRow(self.status)
        f.addRow(note)
        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Save).setText("ذخیره و اتصال")
        bb.button(QDialogButtonBox.Cancel).setText("انصراف")
        bb.accepted.connect(self._save)
        bb.rejected.connect(self.reject)
        f.addRow(bb)
        self.cb.currentTextChanged.connect(self._load)
        btn_test.clicked.connect(self._test)
        btn_del.clicked.connect(self._delete)
        self._load(self.cb.currentText())

    def _load(self, name):
        k, s, p = exchange_keys(name)
        self.key.setText(k)
        self.secret.setText(s)
        self.password.setText(p)
        self.testnet.setChecked(bool(storage.get(f"testnet:{name}", False)))
        self.status.setText("کلید ذخیره‌شده دارد ✓" if k else "کلیدی ذخیره نشده")
        self.status.setStyleSheet(f"color: {TEAL if k else MUTED};")

    def values(self):
        return (self.cb.currentText(), self.key.text().strip(), self.secret.text().strip(),
                self.password.text().strip(), self.testnet.isChecked())

    def _test(self):
        from didban.data.exchange import ExchangeClient
        name, k, s, p, tn = self.values()
        self.status.setText("در حال تست...")
        self.status.setStyleSheet(f"color: {MUTED};")

        def job():
            return ExchangeClient(name, self.market_type, k, s, p, testnet=tn).test()

        def done(msg, err):
            self.status.setText(msg if not err else f"خطا: {err}")
            self.status.setStyleSheet(f"color: {TEAL if not err else RED};")
        self._b = run_async(job, done)

    def _delete(self):
        name = self.cb.currentText()
        for part in ("key", "secret", "password"):
            storage.set_secret(f"{name}:{part}", "")
        self._load(name)

    def _save(self):
        name, k, s, p, tn = self.values()
        if self.remember.isChecked():
            storage.set_secret(f"{name}:key", k)
            storage.set_secret(f"{name}:secret", s)
            storage.set_secret(f"{name}:password", p)
        storage.put(f"testnet:{name}", tn)
        self.accept()


# ======================= AI engine =======================
class AiDialog(QDialog):
    def __init__(self, parent, current: str):
        super().__init__(parent)
        self.setWindowTitle("انتخاب هوش مصنوعی تحلیل چارت")
        self.setMinimumWidth(520)
        f = QFormLayout(self)
        self.cb = QComboBox()
        for k, e in ENGINES.items():
            self.cb.addItem(e.title, k)
        self.cb.setCurrentIndex(max(0, self.cb.findData(current)))
        self.key = QLineEdit()
        self.key.setEchoMode(QLineEdit.Password)
        self.model = QLineEdit()
        self.base = QLineEdit()
        self.weight = QDoubleSpinBox(decimals=0, minimum=0, maximum=100, value=float(storage.get("ai_weight", 55)), suffix=" %")
        self.desc = QLabel()
        self.desc.setWordWrap(True)
        self.desc.setObjectName("muted")
        self.status = QLabel()
        self.status.setWordWrap(True)
        btn_test = QPushButton("تست")
        f.addRow("موتور", self.cb)
        f.addRow(self.desc)
        f.addRow("API Key", self.key)
        f.addRow("مدل", self.model)
        f.addRow("آدرس سرور", self.base)
        f.addRow("وزن هوش مصنوعی در تصمیم", self.weight)
        f.addRow(btn_test, self.status)
        bb = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Save).setText("ذخیره")
        bb.button(QDialogButtonBox.Cancel).setText("انصراف")
        bb.accepted.connect(self._save)
        bb.rejected.connect(self.reject)
        f.addRow(bb)
        self.cb.currentIndexChanged.connect(self._load)
        btn_test.clicked.connect(self._test)
        self._load()

    def engine(self):
        return ENGINES[self.cb.currentData()]

    def _load(self, *_):
        e = self.engine()
        llm = e.kind == "llm"
        for w in (self.key, self.model, self.base):
            w.setEnabled(llm)
        self.key.setEnabled(llm and e.needs_key)
        self.key.setText(storage.get_secret(f"ai:{e.key}") if llm else "")
        self.model.setText(storage.get(f"ai_model:{e.key}", e.default_model))
        self.base.setText(storage.get(f"ai_base:{e.key}", e.base_url))
        self.desc.setText({
            "none": "فقط رأی اندیکاتورها (EMA، MACD، RSI، ADX). سریع و بدون هزینه.",
            "ml": "مدل یادگیری ماشین روی کامپیوتر خودت، روی کندل‌های اخیر هر ارز آموزش می‌بیند. رایگان و آفلاین.",
            "llm": "خلاصه چارت و اندیکاتورها برای مدل زبانی فرستاده می‌شود و تحلیل + حد سود و ضرر پیشنهادی برمی‌گرداند. "
                   "فقط یک بار در هر کندل جدید صدا زده می‌شود تا هزینه کم بماند." +
                   (" Ollama را از ollama.com نصب کن و مدل را pull کن." if e.key == "ollama" else ""),
        }[e.kind])
        self.status.setText("")

    def _test(self):
        from didban.core import analyzers as A
        from didban.data.exchange import DemoFeed
        from didban.core import indicators as ind
        e = self.engine()
        self.status.setText("در حال تست...")
        k, m, b = self.key.text().strip(), self.model.text().strip(), self.base.text().strip()

        def job():
            an = A.create(e.key, 8, k, m, b)
            if an is None:
                return "آماده ✓"
            df = ind.add_all(DemoFeed().ohlcv("BTC/USDT", "1h", 400), 9, 21)
            r = an.analyze("BTC/USDT", df, "1h")
            if r.prob_up is None:
                raise RuntimeError(" ".join(r.reasons))
            return f"پاسخ گرفت ✓  احتمال صعود {r.prob_up*100:.0f}%"

        def done(msg, err):
            self.status.setText(msg or f"خطا: {err}")
            self.status.setStyleSheet(f"color: {TEAL if msg else RED};")
        self._b = run_async(job, done)

    def _save(self):
        e = self.engine()
        if e.kind == "llm":
            storage.set_secret(f"ai:{e.key}", self.key.text().strip())
            storage.put(f"ai_model:{e.key}", self.model.text().strip() or e.default_model)
            storage.put(f"ai_base:{e.key}", self.base.text().strip() or e.base_url)
        storage.put("ai_engine", e.key)
        storage.put("ai_weight", self.weight.value())
        self.accept()


# ======================= add symbols =======================
class SymbolPicker(QDialog):
    """Searchable list of every market on the exchange; check to add to the watchlist."""

    def __init__(self, parent, all_symbols: list[str], watched: list[str]):
        super().__init__(parent)
        self.setWindowTitle("افزودن ارز")
        self.resize(420, 560)
        v = QVBoxLayout(self)
        self.search = QLineEdit(placeholderText=f"جستجو بین {len(all_symbols)} بازار...")
        self.lst = QListWidget()
        self.lst.setSelectionMode(QAbstractItemView.NoSelection)
        watched_set = set(watched)
        for s in all_symbols:
            it = QListWidgetItem(s)
            it.setFlags(it.flags() | Qt.ItemIsUserCheckable)
            it.setCheckState(Qt.Checked if s in watched_set else Qt.Unchecked)
            self.lst.addItem(it)
        v.addWidget(self.search)
        v.addWidget(self.lst)
        bb = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        bb.button(QDialogButtonBox.Ok).setText("افزودن")
        bb.button(QDialogButtonBox.Cancel).setText("انصراف")
        bb.accepted.connect(self.accept)
        bb.rejected.connect(self.reject)
        v.addWidget(bb)
        self.search.textChanged.connect(self._filter)
        self.search.setFocus()

    def _filter(self, text):
        t = text.upper().strip()
        for i in range(self.lst.count()):
            it = self.lst.item(i)
            it.setHidden(bool(t) and t not in it.text())

    def selected(self) -> list[str]:
        return [self.lst.item(i).text() for i in range(self.lst.count()) if self.lst.item(i).checkState() == Qt.Checked]
