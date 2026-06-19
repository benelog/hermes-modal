# 카카오톡 북클럽 요약봇 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Telegram에서 "카카오톡 ABC방 요약해줘"라고 보내면 카카오톡 '아카라카북클럽' 방의 최근 메시지를 한국어로 요약해 Telegram으로 돌려주는 봇을, 수집(디바이스)·저장/조회(Modal)·요약(Hermes)으로 나눠 만든다.

**Architecture:** 안드로이드 폰의 AutoJs6 접근성 스크립트가 '아카라카북클럽' 방을 열 때 화면 메시지를 긁어 Modal `kakao_ingest`(HTTPS POST)로 보낸다. Modal은 메시지를 `modal.Dict`에 중복 제거하며 적재하고 `kakao_messages`(HTTPS GET)로 기간별 조회를 제공한다. 기존 Telegram gateway의 Hermes가 신규 skill로 조회 헬퍼를 실행해 결과를 요약·전달한다. cron 자동요약은 범위 밖(나중에).

**Tech Stack:** Python 3.11, Modal(`@fastapi_endpoint`, `modal.Dict`, Secret), FastAPI, Hermes Agent(skill + 터미널 toolset), AutoJs6(JavaScript, AccessibilityService), `unittest`.

설계 출처: `docs/superpowers/specs/2026-06-20-kakao-bookclub-summary-bot-design.md`

---

## File Structure

- Create `scripts/kakao/__init__.py` — `kakao` 패키지 표시(빈 파일). collector 엔드포인트가 `from kakao.collector_core import ...` 하도록.
- Create `scripts/kakao/collector_core.py` — Modal 의존성 없는 순수 로직(중복키·기간파싱·정규화·필터·만료). 유닛 테스트 대상.
- Create `tests/test_kakao_collector_core.py` — collector_core 유닛 테스트.
- Modify `modal_app.py` — 이미지에 `fastapi[standard]` 추가, `common_env`에 `KAKAO_MESSAGES_URL` 추가, `modal.Dict` 선언, `kakao_ingest`/`kakao_messages` 엔드포인트 2개 추가. 기존 `gateway`는 변경하지 않음.
- Create `scripts/cron/kakao_fetch.py` — Hermes가 실행하는 조회 헬퍼. `KAKAO_MESSAGES_URL`/`KAKAO_COLLECTOR_TOKEN`를 env에서 읽어 GET 후 JSON을 stdout으로 출력. `prepare_runtime.py`가 `~/.hermes/scripts/`로 동기화.
- Create `tests/test_kakao_fetch.py` — `build_url` 순수 함수 테스트.
- Create `scripts/skills/knowledge-base/kakao-room-summary/SKILL.md` — "카톡 …요약" 요청 시 헬퍼 실행→한국어 요약→답장. `prepare_runtime.py`가 `~/.hermes/skills/`로 동기화.
- Modify `scripts/create_modal_secret.py` — `KAKAO_COLLECTOR_TOKEN`를 시크릿에 포함(없으면 자동 생성).
- Modify `scripts/prepare_runtime.py` — `write_env_file`의 키 목록에 `KAKAO_COLLECTOR_TOKEN`, `KAKAO_MESSAGES_URL` 추가.
- Modify `tests/test_prepare_runtime.py` — `write_env_file`가 신규 키를 기록하는지 테스트 추가.
- Create `tests/test_create_modal_secret.py` — 토큰 자동 생성/주입 테스트.
- Create `scripts/autojs/kakao_collect.js` — AutoJs6 접근성 수집 스크립트(디바이스). 수동 테스트.
- Create `scripts/autojs/README.md` — 폰 셋업·calibration·테스트 가이드.
- Modify `README.md` — 기능 개요/배포/디바이스 셋업/테스트 런북 섹션 추가.

---

## Task 1: collector_core 순수 로직 + 유닛 테스트

**Files:**
- Create: `scripts/kakao/__init__.py`
- Create: `scripts/kakao/collector_core.py`
- Test: `tests/test_kakao_collector_core.py`

- [ ] **Step 1: 빈 패키지 파일 생성**

`scripts/kakao/__init__.py` 를 빈 파일로 만든다(내용 없음).

```python
```

- [ ] **Step 2: 실패하는 테스트 작성**

`tests/test_kakao_collector_core.py`:

```python
from pathlib import Path
import importlib.util
import unittest
from datetime import datetime, timedelta, timezone


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "kakao" / "collector_core.py"
spec = importlib.util.spec_from_file_location("collector_core", MODULE_PATH)
collector_core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector_core)


class MessageKeyTests(unittest.TestCase):
    def test_same_message_same_key_regardless_of_received_time(self):
        a = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        b = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        self.assertEqual(a, b)

    def test_different_text_different_key(self):
        a = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        b = collector_core.message_key("아카라카북클럽", "홍길동", "잘가", "오후 3:25")
        self.assertNotEqual(a, b)

    def test_key_is_prefixed_with_room(self):
        key = collector_core.message_key("아카라카북클럽", "홍길동", "안녕", "오후 3:25")
        self.assertTrue(key.startswith("아카라카북클럽|"))


class ParseSinceTests(unittest.TestCase):
    def test_default_is_one_day(self):
        self.assertEqual(collector_core.parse_since_to_timedelta(None), timedelta(days=1))
        self.assertEqual(collector_core.parse_since_to_timedelta(""), timedelta(days=1))

    def test_days_variants(self):
        self.assertEqual(collector_core.parse_since_to_timedelta("3day"), timedelta(days=3))
        self.assertEqual(collector_core.parse_since_to_timedelta("2d"), timedelta(days=2))
        self.assertEqual(collector_core.parse_since_to_timedelta("5일"), timedelta(days=5))
        self.assertEqual(collector_core.parse_since_to_timedelta("7"), timedelta(days=7))

    def test_hours_variant(self):
        self.assertEqual(collector_core.parse_since_to_timedelta("12h"), timedelta(hours=12))

    def test_garbage_falls_back_to_one_day(self):
        self.assertEqual(collector_core.parse_since_to_timedelta("어쩌고"), timedelta(days=1))


class NormalizeItemTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 6, 20, 0, 0, tzinfo=timezone.utc)

    def test_missing_room_raises(self):
        with self.assertRaises(ValueError):
            collector_core.normalize_item({"text": "hi"}, self.now)

    def test_blank_text_raises(self):
        with self.assertRaises(ValueError):
            collector_core.normalize_item({"room": "ABC", "text": "  "}, self.now)

    def test_normalized_shape(self):
        rec = collector_core.normalize_item(
            {"room": " ABC ", "sender": " 홍길동 ", "text": "안녕", "ts": "오후 3:25"},
            self.now,
        )
        self.assertEqual(rec["room"], "ABC")
        self.assertEqual(rec["sender"], "홍길동")
        self.assertEqual(rec["text"], "안녕")
        self.assertEqual(rec["client_time"], "오후 3:25")
        self.assertEqual(rec["received_at"], "2026-06-20T00:00:00+00:00")


class SelectMessagesTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        self.items = [
            {"room": "ABC", "sender": "a", "text": "old", "client_time": "",
             "received_at": (self.now - timedelta(days=3)).isoformat()},
            {"room": "ABC", "sender": "b", "text": "recent", "client_time": "",
             "received_at": (self.now - timedelta(hours=2)).isoformat()},
            {"room": "OTHER", "sender": "c", "text": "otherroom", "client_time": "",
             "received_at": (self.now - timedelta(hours=1)).isoformat()},
        ]

    def test_since_window_filters_old(self):
        out = collector_core.select_messages(self.items, None, "1day", self.now)
        texts = [r["text"] for r in out]
        self.assertIn("recent", texts)
        self.assertIn("otherroom", texts)
        self.assertNotIn("old", texts)

    def test_room_filter(self):
        out = collector_core.select_messages(self.items, "ABC", "1day", self.now)
        self.assertEqual([r["text"] for r in out], ["recent"])

    def test_sorted_oldest_first(self):
        out = collector_core.select_messages(self.items, None, "7day", self.now)
        received = [r["received_at"] for r in out]
        self.assertEqual(received, sorted(received))


class ExpiredKeysTests(unittest.TestCase):
    def test_returns_keys_older_than_retention(self):
        now = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        items = [
            ("k_old", {"received_at": (now - timedelta(days=20)).isoformat()}),
            ("k_new", {"received_at": (now - timedelta(days=1)).isoformat()}),
        ]
        self.assertEqual(collector_core.expired_keys(items, now, retention_days=14), ["k_old"])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `python -m pytest tests/test_kakao_collector_core.py -v`
Expected: FAIL — `collector_core.py` 가 없어 import/속성 오류.
(대안: `python tests/test_kakao_collector_core.py -v`)

- [ ] **Step 4: collector_core 구현**

`scripts/kakao/collector_core.py`:

```python
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
    raw = "".join([room or "", sender or "", text or "", client_time or ""])
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
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `python -m pytest tests/test_kakao_collector_core.py -v`
Expected: PASS (모든 테스트 통과).

- [ ] **Step 6: 커밋**

```bash
git add scripts/kakao/__init__.py scripts/kakao/collector_core.py tests/test_kakao_collector_core.py
git commit -m "카카오 메시지 수집기 순수 로직과 유닛 테스트 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Modal collector 엔드포인트 (`kakao_ingest` / `kakao_messages`)

**Files:**
- Modify: `modal_app.py` (이미지 pip_install, `common_env`, `modal.Dict` 선언, 신규 함수 2개)

이 태스크는 Modal 런타임 의존이라 pytest 대신 **배포 후 curl 수동 검증**으로 확인한다(순수 로직은 Task 1에서 검증됨).

- [ ] **Step 1: 이미지에 fastapi 추가**

`modal_app.py`의 `image` 정의에서 `pip_install` 블록을 수정한다.

기존:
```python
    .pip_install(
        "hermes-agent[messaging,mcp,cron,cli] @ git+https://github.com/NousResearch/hermes-agent.git",
        "modal>=1.0.0,<2",
    )
```
변경:
```python
    .pip_install(
        "hermes-agent[messaging,mcp,cron,cli] @ git+https://github.com/NousResearch/hermes-agent.git",
        "modal>=1.0.0,<2",
        "fastapi[standard]",
    )
```

- [ ] **Step 2: `modal.Dict`와 상수 선언**

`modal_app.py`에서 볼륨 선언 바로 아래(`qmd_config_volume = ...` 다음 줄)에 추가한다.

```python

# KakaoTalk message collector store. modal.Dict is shared across containers with
# fresh reads, avoiding the commit/reload staleness a Volume would have between
# the ingest endpoint and the messages endpoint.
KAKAO_DICT_NAME = "kakao-collect"
KAKAO_SCRIPTS_PATH = "/opt/hermes-modal/scripts"
kakao_dict = modal.Dict.from_name(KAKAO_DICT_NAME, create_if_missing=True)
```

- [ ] **Step 3: `common_env`에 메시지 조회 URL 추가**

`common_env` 딕셔너리에 한 줄 추가한다(값의 정확한 호스트는 Step 6 배포 후 확인해 교정).

```python
    # KakaoTalk collector: Hermes' kakao-room-summary skill reads this to fetch
    # collected messages. Confirm the exact URL from the deploy output and fix
    # if the workspace/label host differs.
    "KAKAO_MESSAGES_URL": "https://benelog--kakao-messages.modal.run",
```

- [ ] **Step 4: 수집 엔드포인트 `kakao_ingest` 추가**

`modal_app.py` 맨 끝(`doctor` 함수 뒤)에 추가한다.

```python
@app.function(
    image=image,
    secrets=[secret],
    timeout=60,
    env=common_env,
)
@modal.fastapi_endpoint(method="POST", label="kakao-ingest")
def kakao_ingest(item: dict, token: str = ""):
    """Receive one scraped KakaoTalk message from the device and store it.

    `token` is a query parameter validated against KAKAO_COLLECTOR_TOKEN.
    `item` is the JSON body: {room, sender, text, ts|client_time}.
    """
    import os
    import sys
    from datetime import datetime, timezone

    from fastapi import HTTPException

    if token != os.environ.get("KAKAO_COLLECTOR_TOKEN", ""):
        raise HTTPException(status_code=401, detail="unauthorized")

    sys.path.insert(0, KAKAO_SCRIPTS_PATH)
    from kakao.collector_core import message_key, normalize_item

    try:
        rec = normalize_item(item, datetime.now(timezone.utc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    key = message_key(rec["room"], rec["sender"], rec["text"], rec["client_time"])
    kakao_dict[key] = rec
    return {"ok": True, "key": key}
```

- [ ] **Step 5: 조회 엔드포인트 `kakao_messages` 추가**

`modal_app.py` 맨 끝(`kakao_ingest` 뒤)에 추가한다.

```python
@app.function(
    image=image,
    secrets=[secret],
    timeout=60,
    env=common_env,
)
@modal.fastapi_endpoint(method="GET", label="kakao-messages")
def kakao_messages(token: str = "", since: str = "1day", room: str = ""):
    """Return collected messages within the `since` window, oldest first.

    Prunes records older than the 14-day retention on each read (best effort).
    """
    import os
    import sys
    from datetime import datetime, timezone

    from fastapi import HTTPException

    if token != os.environ.get("KAKAO_COLLECTOR_TOKEN", ""):
        raise HTTPException(status_code=401, detail="unauthorized")

    sys.path.insert(0, KAKAO_SCRIPTS_PATH)
    from kakao.collector_core import expired_keys, select_messages

    now = datetime.now(timezone.utc)
    items_with_keys = list(kakao_dict.items())

    stale = expired_keys(items_with_keys, now, retention_days=14)
    for key in stale:
        try:
            del kakao_dict[key]
        except KeyError:
            pass

    stale_set = set(stale)
    records = [rec for key, rec in items_with_keys if key not in stale_set]
    selected = select_messages(records, room or None, since, now)
    return {"ok": True, "count": len(selected), "messages": selected}
```

- [ ] **Step 6: 배포하고 엔드포인트 URL 확인**

(주: Task 5에서 `KAKAO_COLLECTOR_TOKEN`을 시크릿에 넣은 뒤 배포해야 인증이 동작한다. Task 2~5를 모두 구현한 뒤 한 번에 배포·검증해도 된다.)

Run: `modal deploy modal_app.py`
Expected: 출력에 `kakao-ingest`, `kakao-messages` 두 엔드포인트 URL이 표시됨. 표시된 messages URL이 `common_env["KAKAO_MESSAGES_URL"]`와 다르면 그 값으로 교정 후 재배포.

- [ ] **Step 7: curl 수동 검증**

```bash
TOKEN="<KAKAO_COLLECTOR_TOKEN 값>"
ING="<배포된 kakao-ingest URL>"
MSG="<배포된 kakao-messages URL>"

# 1) 인증 실패는 401
curl -s -o /dev/null -w "%{http_code}\n" "$MSG?token=wrong&since=1day"   # 기대: 401

# 2) 샘플 적재
curl -s -X POST "$ING?token=$TOKEN" -H 'Content-Type: application/json' \
  -d '{"room":"아카라카북클럽","sender":"홍길동","text":"오늘 책 30p까지 읽었어요","ts":"오후 3:25"}'
# 기대: {"ok":true,"key":"아카라카북클럽|..."}

# 3) 같은 메시지 재적재(중복 제거 확인 — count 안 늘어야 함)
curl -s -X POST "$ING?token=$TOKEN" -H 'Content-Type: application/json' \
  -d '{"room":"아카라카북클럽","sender":"홍길동","text":"오늘 책 30p까지 읽었어요","ts":"오후 3:25"}'

# 4) 조회
curl -s "$MSG?token=$TOKEN&since=1day"
# 기대: {"ok":true,"count":1,"messages":[{..."received_at":...}]}
```
Expected: 401 동작, 적재 후 `count`가 1, 중복 재적재해도 1 유지.

- [ ] **Step 8: 커밋**

```bash
git add modal_app.py
git commit -m "Modal 카카오 메시지 수집/조회 엔드포인트 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Hermes 조회 헬퍼 `kakao_fetch.py` + 테스트

**Files:**
- Create: `scripts/cron/kakao_fetch.py`
- Test: `tests/test_kakao_fetch.py`

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/test_kakao_fetch.py`:

```python
from pathlib import Path
import importlib.util
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "cron" / "kakao_fetch.py"
spec = importlib.util.spec_from_file_location("kakao_fetch", MODULE_PATH)
kakao_fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kakao_fetch)


class BuildUrlTests(unittest.TestCase):
    def test_includes_token_and_since(self):
        url = kakao_fetch.build_url("https://x--kakao-messages.modal.run", "tok", "1day", "")
        self.assertIn("token=tok", url)
        self.assertIn("since=1day", url)
        self.assertNotIn("room=", url)

    def test_includes_room_when_given(self):
        url = kakao_fetch.build_url("https://x--kakao-messages.modal.run/", "tok", "3day", "아카라카북클럽")
        self.assertIn("room=", url)
        self.assertIn("since=3day", url)
        # base의 뒤쪽 슬래시는 중복되지 않아야 함
        self.assertNotIn(".run//?", url)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python -m pytest tests/test_kakao_fetch.py -v`
Expected: FAIL — `kakao_fetch.py` 없음.

- [ ] **Step 3: 헬퍼 구현**

`scripts/cron/kakao_fetch.py`:

```python
#!/usr/bin/env python3
"""Fetch collected KakaoTalk messages from the Modal collector and print JSON.

Hermes' kakao-room-summary skill runs this, then summarizes the output.
Reads KAKAO_MESSAGES_URL and KAKAO_COLLECTOR_TOKEN from the environment.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request


def build_url(base: str, token: str, since: str, room: str) -> str:
    query = {"token": token, "since": since}
    if room:
        query["room"] = room
    return base.rstrip("/") + "?" + urllib.parse.urlencode(query)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--since", default="1day")
    parser.add_argument("--room", default="")
    args = parser.parse_args()

    base = os.environ.get("KAKAO_MESSAGES_URL", "").strip()
    token = os.environ.get("KAKAO_COLLECTOR_TOKEN", "").strip()
    if not base or not token:
        print(json.dumps(
            {"ok": False, "error": "KAKAO_MESSAGES_URL/KAKAO_COLLECTOR_TOKEN not set"},
            ensure_ascii=False,
        ))
        return 1

    url = build_url(base, token, args.since, args.room)
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            data = resp.read().decode("utf-8")
    except Exception as exc:  # noqa: BLE001 - surface any failure as JSON for the skill
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        return 1

    sys.stdout.write(data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_kakao_fetch.py -v`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add scripts/cron/kakao_fetch.py tests/test_kakao_fetch.py
git commit -m "Hermes용 카카오 메시지 조회 헬퍼와 테스트 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Hermes skill `kakao-room-summary`

**Files:**
- Create: `scripts/skills/knowledge-base/kakao-room-summary/SKILL.md`

기존 `english` 스킬과 동일한 구조(frontmatter + Trigger/Procedure/Constraints, bash로 스크립트 실행→JSON 파싱→한국어 답장). pytest 대상이 아니며 E2E(Task 7)에서 검증.

- [ ] **Step 1: SKILL.md 작성**

`scripts/skills/knowledge-base/kakao-room-summary/SKILL.md`:

```markdown
---
name: kakao-room-summary
description: 카카오톡 '아카라카북클럽' 방에서 수집된 최근 메시지를 요약해 전달한다.
---

# KakaoTalk 북클럽 대화 요약

## Trigger

사용자가 카카오톡 대화방 요약을 요청할 때 사용한다. 예: "카카오톡 ABC방 요약해줘",
"아카라카북클럽 오늘 대화 요약", "카톡 북클럽 3일치 요약". 수집 대상 방은 하나뿐이므로
방 이름 표현이 정확하지 않아도 이 방을 요약하면 된다.

## Procedure

1. 요청에서 기간을 추출한다. "오늘"/언급 없음 → `1day`, "3일치"/"3일" → `3day`,
   "이번주" 등 모호하면 `7day`. 시간 단위는 `12h`처럼 쓸 수 있다.
2. 조회 헬퍼를 실행해 수집된 메시지를 가져온다(기간은 추출값으로 치환):
   ```bash
   python /root/.hermes/scripts/kakao_fetch.py --since 1day
   ```
3. 출력 JSON을 파싱한다. 형태:
   - `ok` (bool)
   - `count` (int)
   - `messages`: 각 항목에 `sender`, `text`, `client_time`(카톡에 보였던 시각),
     `received_at`(수집 시각, UTC)
4. `ok`가 false거나 `count`가 0이면, 해당 기간에 수집된 메시지가 없다고 한국어로 알리고,
   디바이스에서 방을 열어 스크롤해야 수집된다는 점을 한 줄로 덧붙인다.
5. 메시지가 있으면 한국어로 간결하게 요약한다. Telegram 친화적으로:
   - 제목: `## 아카라카북클럽 대화 요약`
   - 첫 줄에 기준을 표기: `기준: 최근 <기간> · <count>건`
   - 핵심 화제 3~6개를 불릿으로, 누가 무엇을 말했는지 위주로.
   - 결정/약속/일정(다음 모임, 읽을 범위 등)이 있으면 별도로 강조.
6. 요약만 답장한다. 원문 메시지를 그대로 나열하지 않는다.

## Constraints

- 정상 요약 요청에는 되묻지 말고 바로 수행한다.
- 수집된 메시지(`messages`)만 근거로 삼고 내용을 지어내지 않는다.
- 사진/스티커/이모티콘은 `[사진]` 등으로 들어올 수 있으니 텍스트 위주로 요약한다.
- 메시지 유실 가능성(디바이스가 방을 연 범위만 수집)을 전제로, 단정적 표현을 피한다.
```

- [ ] **Step 2: 동기화 경로 확인(로컬 점검)**

Run: `python - <<'PY'`
```python
from pathlib import Path
p = Path("scripts/skills/knowledge-base/kakao-room-summary/SKILL.md")
print("exists:", p.exists())
print(p.read_text(encoding="utf-8").splitlines()[0])  # 기대: ---
PY
```
Expected: `exists: True` 와 `---`. (런타임 동기화는 `prepare_runtime.sync_skills_dir()`가 부팅 시 `~/.hermes/skills/`로 수행 — 별도 수정 불필요.)

- [ ] **Step 3: 커밋**

```bash
git add scripts/skills/knowledge-base/kakao-room-summary/SKILL.md
git commit -m "Hermes 카카오 북클럽 요약 스킬 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 시크릿/환경변수 배선

**Files:**
- Modify: `scripts/create_modal_secret.py` (`KAKAO_COLLECTOR_TOKEN` 생성/포함)
- Modify: `scripts/prepare_runtime.py` (`write_env_file` 키 추가)
- Test: `tests/test_create_modal_secret.py` (신규), `tests/test_prepare_runtime.py` (추가)

- [ ] **Step 1: create_modal_secret 실패 테스트 작성**

`tests/test_create_modal_secret.py`:

```python
from pathlib import Path
import importlib.util
import os
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "create_modal_secret.py"
spec = importlib.util.spec_from_file_location("create_modal_secret", MODULE_PATH)
cms = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cms)


class KakaoTokenTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        # ~/.hermes/.env 등 로컬 파일 영향 제거
        cms.LOCAL_ENV = Path(self._tmp.name) / ".env"
        cms.LOCAL_AUTH = Path(self._tmp.name) / "auth.json"
        self._saved = os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)
        # build_secret_values는 TELEGRAM_BOT_TOKEN을 요구하지 않지만 main()만 요구
        os.environ["TELEGRAM_BOT_TOKEN"] = "test-bot-token"

    def tearDown(self):
        self._tmp.cleanup()
        os.environ.pop("TELEGRAM_BOT_TOKEN", None)
        if self._saved is not None:
            os.environ["KAKAO_COLLECTOR_TOKEN"] = self._saved

    def test_token_is_generated_when_absent(self):
        values = cms.build_secret_values(None)
        self.assertIn("KAKAO_COLLECTOR_TOKEN", values)
        self.assertGreaterEqual(len(values["KAKAO_COLLECTOR_TOKEN"]), 20)

    def test_token_from_env_is_used(self):
        os.environ["KAKAO_COLLECTOR_TOKEN"] = "fixed-token-value-123"
        try:
            values = cms.build_secret_values(None)
            self.assertEqual(values["KAKAO_COLLECTOR_TOKEN"], "fixed-token-value-123")
        finally:
            os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python -m pytest tests/test_create_modal_secret.py -v`
Expected: FAIL — `KAKAO_COLLECTOR_TOKEN`이 values에 없음.

- [ ] **Step 3: create_modal_secret 구현 수정**

`scripts/create_modal_secret.py`의 `build_secret_values` 함수에서, `TELEGRAM_WEBHOOK_SECRET`을 설정하는 줄들 바로 뒤에 토큰 생성 블록을 추가한다.

기존(앵커):
```python
    values["TELEGRAM_WEBHOOK_SECRET"] = (
        os.environ.get("TELEGRAM_WEBHOOK_SECRET")
        or local.get("TELEGRAM_WEBHOOK_SECRET")
        or secrets.token_urlsafe(32)
    )
```
바로 뒤에 추가:
```python
    values["KAKAO_COLLECTOR_TOKEN"] = (
        os.environ.get("KAKAO_COLLECTOR_TOKEN")
        or local.get("KAKAO_COLLECTOR_TOKEN")
        or secrets.token_urlsafe(32)
    )
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_create_modal_secret.py -v`
Expected: PASS.

- [ ] **Step 5: prepare_runtime write_env_file 테스트 추가**

`tests/test_prepare_runtime.py`의 `PrepareRuntimeTests` 클래스 안에 메서드를 추가한다(파일 상단 import에 `tempfile`, `os`, `Path`는 이미 있음).

```python
    def test_write_env_file_includes_kakao_keys(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            saved_home = prepare_runtime.HERMES_HOME
            saved_url = os.environ.get("KAKAO_MESSAGES_URL")
            saved_token = os.environ.get("KAKAO_COLLECTOR_TOKEN")
            prepare_runtime.HERMES_HOME = Path(tmpdir)
            os.environ["KAKAO_MESSAGES_URL"] = "https://x--kakao-messages.modal.run"
            os.environ["KAKAO_COLLECTOR_TOKEN"] = "tok-123"
            try:
                prepare_runtime.write_env_file()
                content = (Path(tmpdir) / ".env").read_text(encoding="utf-8")
            finally:
                prepare_runtime.HERMES_HOME = saved_home
                if saved_url is None:
                    os.environ.pop("KAKAO_MESSAGES_URL", None)
                else:
                    os.environ["KAKAO_MESSAGES_URL"] = saved_url
                if saved_token is None:
                    os.environ.pop("KAKAO_COLLECTOR_TOKEN", None)
                else:
                    os.environ["KAKAO_COLLECTOR_TOKEN"] = saved_token
            self.assertIn('KAKAO_MESSAGES_URL="https://x--kakao-messages.modal.run"', content)
            self.assertIn('KAKAO_COLLECTOR_TOKEN="tok-123"', content)
```

- [ ] **Step 6: 테스트 실패 확인**

Run: `python -m pytest tests/test_prepare_runtime.py::PrepareRuntimeTests::test_write_env_file_includes_kakao_keys -v`
Expected: FAIL — `write_env_file`이 아직 카카오 키를 기록하지 않음.

- [ ] **Step 7: prepare_runtime write_env_file 수정**

`scripts/prepare_runtime.py`의 `write_env_file` 함수 내 `keys` 리스트에 두 키를 추가한다.

기존(끝부분):
```python
        "QMD_RERANK_MODEL",
        "QMD_LLAMA_GPU",
    ]
```
변경:
```python
        "QMD_RERANK_MODEL",
        "QMD_LLAMA_GPU",
        "KAKAO_COLLECTOR_TOKEN",
        "KAKAO_MESSAGES_URL",
    ]
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `python -m pytest tests/test_prepare_runtime.py -v`
Expected: PASS (신규 + 기존 모두).

- [ ] **Step 9: 시크릿 갱신(로컬에서 1회)**

`KAKAO_MESSAGES_URL`은 `common_env`(코드)로 주입되고, `KAKAO_COLLECTOR_TOKEN`은 시크릿으로 주입된다. 토큰을 시크릿에 반영한다.

```bash
python scripts/create_modal_secret.py
```
Expected: 출력 키 목록에 `KAKAO_COLLECTOR_TOKEN` 포함. (값은 출력되지 않음. 토큰 값이 필요하면 `modal secret list`/UI 대신, 디바이스 스크립트에 넣을 값을 직접 정하려면 `KAKAO_COLLECTOR_TOKEN=<원하는값> python scripts/create_modal_secret.py`로 고정.)

- [ ] **Step 10: 커밋**

```bash
git add scripts/create_modal_secret.py scripts/prepare_runtime.py tests/test_create_modal_secret.py tests/test_prepare_runtime.py
git commit -m "카카오 수집기 토큰/URL 시크릿·환경변수 배선

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: AutoJs6 디바이스 수집 스크립트 + 셋업 가이드

**Files:**
- Create: `scripts/autojs/kakao_collect.js`
- Create: `scripts/autojs/README.md`

CI 테스트 불가(폰 디바이스). 스크립트는 calibration(노드 구조 확인) → 선택자 보정 → 수동 수집 테스트로 검증한다. 카톡 화면의 resource-id/레이아웃은 버전마다 달라, 선택자는 **calibration으로 확정하는 설정 상수**로 둔다.

- [ ] **Step 1: 수집 스크립트 작성**

`scripts/autojs/kakao_collect.js`:

```javascript
// 아카라카북클럽 방 메시지 수집기 (AutoJs6, AccessibilityService 기반)
//
// 동작: 카카오톡에서 대상 방을 열면 화면의 메시지를 읽어 Modal /ingest로 POST.
// 위로 자동 스크롤하며 목표 기간 또는 이미 본 메시지에 도달할 때까지 수집.
// 카톡에는 아무것도 보내지 않음(읽기 전용).
//
// 사용 전:
//  1) AutoJs6에 "접근성 권한"을 부여한다.
//  2) 아래 CONFIG를 채운다. SELECTOR 값은 calibrate()로 확인 후 보정한다.
//  3) 대상 방을 화면에 띄운 뒤 이 스크립트를 실행한다.

const CONFIG = {
    ROOM_NAME: "아카라카북클럽",          // 카톡 채팅방 상단에 보이는 정확한 표시명
    INGEST_URL: "https://benelog--kakao-ingest.modal.run", // 배포된 ingest URL
    TOKEN: "<KAKAO_COLLECTOR_TOKEN>",     // 시크릿과 동일한 토큰
    MAX_SCROLLS: 40,                      // 위로 스크롤 최대 횟수(기간 안전장치)
    SCROLL_PAUSE_MS: 700,
    // calibrate()로 확인해 채울 선택자 (카톡 버전에 따라 다름)
    SELECTOR: {
        TITLE_ID: "com.kakao.talk:id/title",          // 채팅방 제목 노드 id
        MESSAGE_TEXT_ID: "com.kakao.talk:id/message", // 말풍선 본문 노드 id
        SENDER_NAME_ID: "com.kakao.talk:id/name",     // 보낸이 이름 노드 id
        TIME_ID: "com.kakao.talk:id/created_at",      // 시각 노드 id
    },
};

const KAKAO_PKG = "com.kakao.talk";

// 화면 노드 트리를 덤프해 위 SELECTOR.* id를 확인하기 위한 보정 도구.
// 대상 방을 띄운 상태에서 이 함수만 호출해 로그를 보고 CONFIG.SELECTOR를 채운다.
function calibrate() {
    function walk(node, depth) {
        if (node == null) return;
        const id = node.id && node.id();
        const text = node.text && node.text();
        const cls = node.className && node.className();
        if ((id && id.indexOf(KAKAO_PKG) === 0) || (text && text.length > 0)) {
            log(" ".repeat(depth) + "[" + cls + "] id=" + id + " text=" + JSON.stringify(text));
        }
        const n = node.childCount ? node.childCount() : 0;
        for (let i = 0; i < n; i++) walk(node.child(i), depth + 1);
    }
    log("=== calibrate: 노드 트리 ===");
    walk(auto.root, 0);
    log("=== end ===");
}

function isTargetRoomOpen() {
    if (currentPackage() !== KAKAO_PKG) return false;
    // 제목 노드 우선, 없으면 화면 어딘가에 방 이름 텍스트가 있는지로 보조 판별
    const title = id(CONFIG.SELECTOR.TITLE_ID).findOne(1000);
    if (title && title.text() && title.text().indexOf(CONFIG.ROOM_NAME) >= 0) return true;
    return text(CONFIG.ROOM_NAME).exists();
}

function postMessage(rec) {
    try {
        const res = http.postJson(CONFIG.INGEST_URL + "?token=" + encodeURIComponent(CONFIG.TOKEN), rec);
        return res && res.statusCode === 200;
    } catch (e) {
        log("post error: " + e);
        return false;
    }
}

// 현재 화면에 보이는 메시지들을 (sender, text, time)로 수집.
// 연속 메시지는 보낸이가 한 번만 보일 수 있어, 직전 보낸이를 승계한다.
function scrapeVisible(seen, lastSenderRef) {
    const texts = id(CONFIG.SELECTOR.MESSAGE_TEXT_ID).find();
    const out = [];
    for (let i = 0; i < texts.size(); i++) {
        const t = texts.get(i);
        const body = (t.text() || "").trim();
        if (!body) continue;

        // 같은 행/부모에서 보낸이·시각 노드를 탐색(없으면 직전 보낸이 승계)
        let sender = "";
        let ctime = "";
        const parent = t.parent();
        if (parent) {
            const nameNode = parent.findOne(id(CONFIG.SELECTOR.SENDER_NAME_ID));
            if (nameNode && nameNode.text()) sender = nameNode.text().trim();
            const timeNode = parent.findOne(id(CONFIG.SELECTOR.TIME_ID));
            if (timeNode && timeNode.text()) ctime = timeNode.text().trim();
        }
        if (!sender) sender = lastSenderRef.value;
        else lastSenderRef.value = sender;

        const dedupe = sender + "" + body + "" + ctime;
        if (seen[dedupe]) continue;
        seen[dedupe] = true;
        out.push({ room: CONFIG.ROOM_NAME, sender: sender, text: body, ts: ctime });
    }
    return out;
}

function main() {
    if (!isTargetRoomOpen()) {
        toast("대상 방(" + CONFIG.ROOM_NAME + ")을 먼저 열어주세요");
        log("대상 방이 화면에 없습니다. 종료.");
        return;
    }
    const seen = {};
    const lastSender = { value: "" };
    let posted = 0;

    for (let s = 0; s < CONFIG.MAX_SCROLLS; s++) {
        const batch = scrapeVisible(seen, lastSender);
        for (let i = 0; i < batch.length; i++) {
            if (postMessage(batch[i])) posted++;
        }
        // 위로 스크롤(과거 메시지 로드). 스크롤이 안 먹으면 종료.
        const before = Object.keys(seen).length;
        scrollUp();
        sleep(CONFIG.SCROLL_PAUSE_MS);
        const grew = Object.keys(seen).length;
        if (grew === before && s > 1) {
            // 더 이상 새 메시지가 안 보이면 맨 위 도달로 간주
            // (한 번 더 확인 후 종료)
            scrapeVisible(seen, lastSender);
            if (Object.keys(seen).length === grew) break;
        }
    }
    toast("수집 완료: " + posted + "건 전송");
    log("posted=" + posted);
}

// 보정이 필요하면 main() 대신 calibrate()를 실행:
// calibrate();
main();
```

- [ ] **Step 2: 셋업 가이드 작성**

`scripts/autojs/README.md`:

```markdown
# 디바이스 수집 셋업 (AutoJs6 + 아카라카북클럽)

카카오톡 '아카라카북클럽' 방의 메시지를 폰에서 긁어 Modal 수집 엔드포인트로 보낸다.
카톡에는 아무것도 보내지 않는다(읽기 전용). 조용한 방(알림 끔)도 동작한다.

## 준비물
- 안드로이드 폰(메인폰 가능). 카카오톡에 본인 계정 로그인, 대상 방 가입 상태.
- [AutoJs6](https://github.com/SuperMonster003/AutoJs6) 설치(무료, MPL-2.0).

## 설치
1. AutoJs6 설치 후 **접근성 권한**을 부여한다(설정 → 접근성 → AutoJs6 켜기).
2. 배터리 최적화에서 AutoJs6와 카카오톡을 제외한다(백그라운드 종료 방지).
3. `scripts/autojs/kakao_collect.js`를 폰의 AutoJs6 스크립트로 가져온다.
4. 스크립트 상단 `CONFIG`를 채운다:
   - `ROOM_NAME`: 채팅방 상단에 보이는 정확한 표시명.
   - `INGEST_URL`: `modal deploy` 출력의 kakao-ingest URL.
   - `TOKEN`: Modal 시크릿의 `KAKAO_COLLECTOR_TOKEN`과 동일한 값
     (`KAKAO_COLLECTOR_TOKEN=<원하는값> python scripts/create_modal_secret.py`로 값을 고정해두면 편하다).

## Calibration (선택자 보정 — 최초 1회 필수)
카톡 버전마다 노드 id가 다르다. 대상 방을 화면에 띄운 뒤, 스크립트 맨 아래를
`main();` 대신 `calibrate();`로 바꿔 한 번 실행한다. AutoJs6 로그에 찍힌
`[클래스] id=... text=...`를 보고 `CONFIG.SELECTOR`의 4개 id를 실제 값으로 맞춘다:
- `MESSAGE_TEXT_ID`: 말풍선 본문이 담긴 TextView의 id
- `SENDER_NAME_ID`: 보낸이 이름 TextView의 id
- `TIME_ID`: 시각(예: 오후 3:25) TextView의 id
- `TITLE_ID`: 상단 방 제목 노드의 id
보정 후 다시 `main();`으로 되돌린다.

## 수집 테스트
1. 대상 방을 연다.
2. 스크립트를 실행한다. 위로 자동 스크롤하며 수집하고, 끝나면 "수집 완료: N건" 토스트.
3. Modal에서 적재 확인:
   ```bash
   curl -s "<kakao-messages URL>?token=$TOKEN&since=1day"
   ```
   `count`와 `messages`가 화면에서 본 메시지와 맞는지 확인.

## 한계
- 방을 열어 스크롤한 범위만 수집된다("열면 캡처", best-effort). 안 열면 수집 안 됨.
- 사진/스티커는 `[사진]` 등 텍스트로만 잡힌다.
- 같은 보낸이의 연속 메시지는 보낸이가 승계 처리된다.
- 카톡 UI가 업데이트되면 calibration을 다시 해야 할 수 있다.
```

- [ ] **Step 3: 디바이스 수동 검증(폰 필요)**

폰에서: 대상 방 열기 → `calibrate()`로 선택자 확정 → `main()` 실행 → "수집 완료" 토스트 → `curl .../kakao-messages?token=...&since=1day`로 적재 확인.
Expected: 화면에서 본 메시지가 `messages`에 들어오고, 같은 방을 다시 수집해도 중복으로 늘지 않음(서버 중복 제거).

(폰이 당장 없으면 이 스텝은 건너뛰고 커밋만 한 뒤, E2E(Task 7)에서 폰으로 검증.)

- [ ] **Step 4: 커밋**

```bash
git add scripts/autojs/kakao_collect.js scripts/autojs/README.md
git commit -m "AutoJs6 카카오 수집 스크립트와 디바이스 셋업 가이드 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: README 문서 + 배포/E2E 런북

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README에 기능 섹션 추가**

`README.md` 끝에 다음 섹션을 추가한다.

```markdown
## 카카오톡 북클럽 대화 요약

Telegram에서 "카카오톡 ABC방 요약해줘"라고 보내면 카카오톡 '아카라카북클럽' 방의
최근 메시지를 한국어로 요약해 Telegram으로 돌려준다. 설계: `docs/superpowers/specs/2026-06-20-kakao-bookclub-summary-bot-design.md`.

구성:
- 디바이스: 안드로이드 폰의 AutoJs6 접근성 스크립트가 대상 방을 열 때 메시지를 긁어
  Modal `kakao-ingest`로 POST한다(읽기 전용). 셋업: `scripts/autojs/README.md`.
- Modal: `kakao_ingest`(POST)/`kakao_messages`(GET) 엔드포인트가 `modal.Dict`에
  14일 보존으로 적재/조회한다. 인증은 시크릿 `KAKAO_COLLECTOR_TOKEN`.
- Hermes: skill `kakao-room-summary`가 `~/.hermes/scripts/kakao_fetch.py`로 조회해
  요약한 뒤 Telegram으로 답장한다.

cron 자동요약(매일 07:00)은 충분히 테스트한 뒤 추가한다(현재 범위 밖).

### 배포/테스트
1. 토큰 시크릿 반영: `KAKAO_COLLECTOR_TOKEN=<값> python scripts/create_modal_secret.py`
2. 배포: `modal deploy modal_app.py` → 출력의 `kakao-ingest`/`kakao-messages` URL 확인.
   `modal_app.py`의 `KAKAO_MESSAGES_URL`이 다르면 교정 후 재배포.
3. 엔드포인트 검증: `scripts/autojs/README.md`의 curl로 적재/조회 확인.
4. 디바이스 셋업: `scripts/autojs/README.md`대로 폰에 스크립트 설치·calibration·수집.
5. E2E: Telegram에 "카카오톡 ABC방 요약해줘" → 한국어 요약 답장 확인.
```

- [ ] **Step 2: 전체 테스트 한 번 실행**

Run: `python -m pytest tests/ -v`
Expected: 모든 테스트 PASS.

- [ ] **Step 3: E2E 수동 검증(배포 + 폰)**

1. Task 5/2 배포 완료 상태에서 폰으로 대상 방 수집(Task 6).
2. Telegram에서 봇에게 "카카오톡 ABC방 요약해줘" 전송.
3. Hermes가 `kakao_fetch.py`를 실행해 메시지를 받아 한국어 요약을 답장하는지 확인.
4. "3일치"로도 보내 기간 변경이 반영되는지 확인.
5. 수집된 게 없을 때 "해당 기간 수집된 메시지가 없습니다" 류로 응답하는지 확인.

Expected: 위 5개가 모두 정상.

- [ ] **Step 4: 커밋**

```bash
git add README.md
git commit -m "카카오 북클럽 요약 기능 README 런북 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 향후(이 계획 범위 밖)
- 매일 07:00 KST cron 자동 요약: 기존 `cron_tick`(이미 07:00에 컨테이너 깨움) + Hermes cron job로
  같은 요약을 돌린다. `cron_jobs/*.json`으로 버전관리(기존 컨벤션). 온디맨드 충분히 검증 후 추가.
- MessengerBot R 알림 방식 병행(실시간 보강) — 동일 `/ingest`로 합류.
- 요약을 카톡 방에 되쓰기(발신 → 리스크 한 단계↑).
- collector 엔드포인트 콜드스타트 단축을 위한 경량 이미지 분리.

---

## Self-Review (작성자 점검 결과)

**Spec 커버리지**: §3 구조(디바이스→collector→Hermes)=Task 6/2/3·4; §4.1 디바이스=Task 6;
§4.2 collector(/ingest·/messages·modal.Dict·토큰·14일)=Task 2(+Task 1 로직); §4.3 skill=Task 4(+Task 3 헬퍼);
§4.4 cron 보류=향후 섹션; §5 데이터모델=Task 1 normalize/message_key; §6 엣지(중복·텍스트·승계·인증·빈기간)=Task 1·2·4;
§7 테스트순서=Task 2 Step7·Task 6 Step3·Task 7 Step3; §9 확정항목(방이름·노드구조·URL·toolset)=Task 6 calibration·Task 2 Step6·Task 7. 누락 없음.

**Placeholder 스캔**: 코드 스텝은 모두 완전한 코드 포함. `<...>` 표기는 배포 산출물(URL/토큰) 주입 지점으로 의도된 런타임 값이며 구현 공백이 아님.

**타입/이름 일관성**: `message_key/parse_since_to_timedelta/normalize_item/select_messages/expired_keys`(Task1) ↔ modal_app import(Task2) 일치;
`build_url`(Task3) ↔ 테스트 일치; 레코드 필드 `room/sender/text/client_time/received_at` 전 구간 일치; env 키 `KAKAO_COLLECTOR_TOKEN/KAKAO_MESSAGES_URL` Task2·3·5·6 일치.
