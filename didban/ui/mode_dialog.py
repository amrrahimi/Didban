"""Startup window: choose trading style (swing / day / long-term)."""
from PySide6.QtCore import Qt
from PySide6.QtWidgets import QDialog, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QFrame

from didban.config import STYLES
from didban.ui.theme import NAVY, TEAL, MUTED


class ModeDialog(QDialog):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("دیدبان · انتخاب سبک معامله")
        self.setMinimumSize(820, 420)
        self.choice = None
        root = QVBoxLayout(self)
        root.setContentsMargins(36, 32, 36, 32)
        title = QLabel("دیدبان")
        title.setStyleSheet(f"font-size: 40px; font-weight: 800; color: {NAVY};")
        sub = QLabel("سبک معامله‌ات را انتخاب کن. تنظیمات تایم‌فریم، اندیکاتورها، حد سود و ضرر و مدل هوش مصنوعی بر اساس آن تنظیم می‌شود.")
        sub.setWordWrap(True)
        sub.setObjectName("muted")
        root.addWidget(title)
        root.addWidget(sub)
        root.addSpacing(18)
        row = QHBoxLayout()
        row.setSpacing(16)
        for key, st in STYLES.items():
            card = QFrame(objectName="card")
            v = QVBoxLayout(card)
            v.setContentsMargins(20, 20, 20, 20)
            name = QLabel(st.title_fa)
            name.setStyleSheet("font-size: 24px; font-weight: 800;")
            desc = QLabel(st.desc_fa)
            desc.setWordWrap(True)
            desc.setStyleSheet(f"color: {MUTED};")
            meta = QLabel(f"EMA {st.ema_fast}/{st.ema_slow} · SL {st.sl_atr}×ATR · TP {st.tp_atr}×ATR")
            meta.setStyleSheet(f"color: {TEAL}; font-family: Consolas, Menlo, monospace;")
            btn = QPushButton("شروع")
            btn.clicked.connect(lambda _=False, k=key: self._pick(k))
            v.addWidget(name)
            v.addWidget(desc)
            v.addStretch()
            v.addWidget(meta)
            v.addWidget(btn)
            row.addWidget(card)
        root.addLayout(row)

    def _pick(self, key):
        self.choice = STYLES[key]
        self.accept()
