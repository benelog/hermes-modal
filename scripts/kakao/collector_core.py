"""Pure logic for the KakaoTalk message collector (no Modal imports).

Used by the Modal `kakao_ingest` / `kakao_messages` endpoints in modal_app.py
and unit-tested in tests/test_kakao_collector_core.py.
"""

from __future__ import annotations

import hashlib
import re
from datetime import datetime, timedelta, timezone


def message_key(room: str, sender: str, text: str, client_time: str) -> str:
    """Stable dedupe id for one message.

    Excludes server receive time so the same message re-scraped later collides
    and is stored once. `client_time` is the time string KakaoTalk shows (minute
    granularity), disambiguating otherwise-identical lines within a day.
    """
    raw = "\x01".join([room or "", sender or "", text or "", client_time or ""])
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()
    return f"{room}|{digest}"


def parse_since_to_timedelta(since: str | None) -> timedelta:
    """Parse '1day' / '3d' / '5일' / '12h' / '7' into a timedelta. Default 1 day."""
    if not since:
        return timedelta(days=1)
    s = str(since).strip().lower()
    m = re.fullmatch(r"(\d+)\s*(day|days|d|일|h|hour|hours|시간)?", s)
    if not m:
        return timedelta(days=1)
    n = int(m.group(1))
    unit = m.group(2) or "day"
    if unit in {"h", "hour", "hours", "시간"}:
        return timedelta(hours=n)
    return timedelta(days=n)


def normalize_item(payload: dict, received_at: datetime) -> dict:
    """Validate/normalize an ingest payload into a stored record.

    Raises ValueError if required fields (room, text) are missing/blank.
    """
    room = (payload.get("room") or "").strip()
    text = payload.get("text")
    if not room:
        raise ValueError("room is required")
    if text is None or str(text).strip() == "":
        raise ValueError("text is required")
    return {
        "room": room,
        "sender": (payload.get("sender") or "").strip(),
        "text": str(text),
        "client_time": (payload.get("client_time") or payload.get("ts") or "").strip(),
        "received_at": received_at.astimezone(timezone.utc).isoformat(),
    }


def _parse_received_at(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value)
    except (ValueError, TypeError):
        return datetime.fromtimestamp(0, tz=timezone.utc)


def select_messages(items, room, since, now: datetime) -> list[dict]:
    """Return stored records within the `since` window, oldest first.

    `items` is an iterable of stored record dicts. Filters by server
    `received_at` (reliable absolute time), optionally by `room`.
    """
    cutoff = now.astimezone(timezone.utc) - parse_since_to_timedelta(since)
    selected = []
    for rec in items:
        if room and rec.get("room") != room:
            continue
        if _parse_received_at(rec.get("received_at", "")) >= cutoff:
            selected.append(rec)
    selected.sort(key=lambda r: r.get("received_at", ""))
    return selected


def expired_keys(items_with_keys, now: datetime, retention_days: int = 14) -> list[str]:
    """Return keys whose `received_at` is older than `retention_days`.

    `items_with_keys` is an iterable of (key, record) pairs.
    """
    cutoff = now.astimezone(timezone.utc) - timedelta(days=retention_days)
    out = []
    for key, rec in items_with_keys:
        if _parse_received_at(rec.get("received_at", "")) < cutoff:
            out.append(key)
    return out
