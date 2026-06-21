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


def extract_since(command: str | None) -> str:
    """Pull a period from a natural-language summarize command into a since token.

    Returns a string `parse_since_to_timedelta` understands ('Nday'/'Nhour'),
    defaulting to '1day'. Examples: '3일치 요약'→'3day', '최근 12시간 요약'→'12hour',
    '오늘 요약'→'1day', '어제 요약'→'2day', '이번주 요약'→'7day'.

    Explicit number+unit wins over keywords, and hours win over days so
    '오늘 최근 12시간만 요약' resolves to '12hour'.
    """
    if not command:
        return "1day"
    s = str(command).strip().lower()

    # Korean units (시간/일) can be followed by more Korean (시간만, 3일치) where \b
    # would fail, so match them without a boundary; ASCII units keep \b since a bare
    # 'd'/'h' could otherwise match inside a word.
    m = re.search(r"(\d+)\s*시간", s) or re.search(r"(\d+)\s*(?:hours?|h)\b", s)
    if m:
        return f"{int(m.group(1))}hour"
    m = re.search(r"(\d+)\s*일", s) or re.search(r"(\d+)\s*(?:days?|d)\b", s)
    if m:
        return f"{int(m.group(1))}day"

    if "어제" in s:
        return "2day"
    if any(k in s for k in ("이번주", "이번 주", "일주일", "한주", "한 주")):
        return "7day"
    if "오늘" in s:
        return "1day"
    return "1day"


_URL_RE = re.compile(r"(?:https?://|www\.)[^\s]+", re.IGNORECASE)
_URL_TRAILING = ".,;:!?'\"…」』】"


def _trim_url(url: str) -> str:
    """Strip trailing chat punctuation that isn't part of the URL.

    Keeps a closing bracket that has a matching opener inside the URL
    (e.g. Wikipedia '..._(novel)') but drops an unmatched one left by the
    surrounding sentence (e.g. '(링크: http://x)').
    """
    url = url.rstrip(_URL_TRAILING)
    while url and url[-1] in ")]}":
        opener = {")": "(", "]": "[", "}": "{"}[url[-1]]
        if url.count(opener) >= url.count(url[-1]):
            break
        url = url[:-1]
    return url.rstrip(_URL_TRAILING)


def extract_urls(items) -> list[str]:
    """Collect every URL shared across messages, de-duplicated in first-seen order.

    Deterministic (not LLM-based) so no shared link is dropped from a summary.
    `items` is an iterable of stored record dicts; reads each record's `text`.
    """
    seen = set()
    urls: list[str] = []
    for rec in items:
        for raw in _URL_RE.findall(rec.get("text") or ""):
            url = _trim_url(raw)
            if url and url not in seen:
                seen.add(url)
                urls.append(url)
    return urls


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
