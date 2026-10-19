"""User settings (watchlist, AI choice, API keys) saved in the user's home folder.

Secrets are stored in the OS keychain (Windows Credential Manager / macOS Keychain) when the
optional `keyring` package is available, otherwise in the settings file with a warning.
"""
from __future__ import annotations

import json
from pathlib import Path

try:
    import keyring  # type: ignore
except ImportError:
    keyring = None

DIR = Path.home() / ".didban"
FILE = DIR / "settings.json"
SERVICE = "didban-trader"


def load() -> dict:
    try:
        return json.loads(FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save(data: dict):
    DIR.mkdir(parents=True, exist_ok=True)
    FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def get(key: str, default=None):
    return load().get(key, default)


def put(key: str, value):
    d = load()
    d[key] = value
    save(d)


def secure_available() -> bool:
    return keyring is not None


def set_secret(name: str, value: str):
    if keyring is not None:
        if value:
            keyring.set_password(SERVICE, name, value)
        else:
            try:
                keyring.delete_password(SERVICE, name)
            except Exception:
                pass
    else:
        d = load()
        d.setdefault("_secrets", {})[name] = value
        save(d)


def get_secret(name: str) -> str:
    if keyring is not None:
        try:
            return keyring.get_password(SERVICE, name) or ""
        except Exception:
            return ""
    return load().get("_secrets", {}).get(name, "")
