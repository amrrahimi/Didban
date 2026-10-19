TEAL, RED, NAVY, SKY, EMBER = "#1c9d7c", "#b5361c", "#3a507f", "#369cc9", "#e35e28"
BG, CARD, INK, MUTED, LINE = "#f3f8f6", "#ffffff", "#24332f", "#5f7470", "#dde7e4"

QSS = f"""
* {{ font-family: 'Vazirmatn', 'Segoe UI', 'Tahoma', sans-serif; font-size: 14px; color: {INK}; }}
QMainWindow, QDialog {{ background: {BG}; }}
QDockWidget {{ titlebar-close-icon: none; }}
QDockWidget::title {{ background: {NAVY}; color: white; padding: 6px 10px; border-top-left-radius: 8px; border-top-right-radius: 8px; }}
QDockWidget > QWidget {{ background: {CARD}; }}
QToolBar {{ background: {NAVY}; border: none; padding: 6px; spacing: 8px; }}
QToolBar QLabel {{ color: white; }}
QPushButton {{ background: {NAVY}; color: white; border: none; border-radius: 8px; padding: 7px 14px; font-weight: 600; }}
QPushButton:hover {{ background: #2f4369; }}
QPushButton:disabled {{ background: #9aa9bf; }}
QPushButton#buy {{ background: {TEAL}; }}
QPushButton#sell {{ background: {RED}; }}
QPushButton#ghost {{ background: transparent; color: {NAVY}; border: 1px solid {LINE}; }}
QComboBox, QSpinBox, QDoubleSpinBox, QLineEdit {{ background: white; border: 1px solid {LINE}; border-radius: 7px; padding: 5px 8px; }}
QListWidget, QTableWidget {{ background: white; border: none; alternate-background-color: #f7faf9; }}
QListWidget::item {{ padding: 6px; border-radius: 6px; }}
QListWidget::item:selected {{ background: #dcf2ea; color: {INK}; }}
QHeaderView::section {{ background: #eef4f2; border: none; padding: 6px; color: {MUTED}; }}
QFrame#card {{ background: white; border: 1px solid {LINE}; border-radius: 12px; }}
QLabel#muted {{ color: {MUTED}; }}
QLabel#big {{ font-size: 28px; font-weight: 800; }}
QTextEdit {{ background: white; border: none; font-family: 'Consolas', 'Menlo', monospace; }}
"""
