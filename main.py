"""Didban - AI trading assistant (desktop GUI). Run: python main.py"""
import sys

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QDialog

from didban.ui.main_window import MainWindow
from didban.ui.mode_dialog import ModeDialog
from didban.ui.theme import QSS


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("Didban")
    app.setLayoutDirection(Qt.RightToLeft)
    app.setStyleSheet(QSS)
    dlg = ModeDialog()
    if dlg.exec() != QDialog.Accepted or dlg.choice is None:
        return 0
    win = MainWindow(dlg.choice)
    win.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
