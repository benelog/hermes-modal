# 카카오톡 북클럽 → BAND 주간 요약 cron Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매주 화·토 07:00 KST에 카카오톡 '아카라카북클럽' 방의 "지난 요약 이후 새 대화"만 요약(행사/책+저자/URL제목/주제·참여자)해 네이버 BAND에 새 글로 게시하고 결과를 텔레그램으로 통지한다.

**Architecture:** 새 Modal 엔드포인트/스케줄을 만들지 않는다. 기존 `cron_tick`(매일 07:00·19:00 KST 컨테이너 깨움)이 돌리는 `hermes cron tick`에 새 Hermes cron job(web+terminal 에이전트)을 등록한다. 그 에이전트가 ① `kakao_bookclub_fetch.py`로 기존 `kakao-messages` 엔드포인트에서 메시지를 받아 볼륨 워터마크 이후만 추리고, ② 책 저자 웹검색·URL 제목 방문으로 본문을 작성한 뒤, ③ `band_post.py`로 BAND에 게시하고 성공 시에만 워터마크를 전진시킨다.

**Tech Stack:** Python 3.11, Hermes Agent cron(web+terminal toolset), 기존 Modal `kakao-messages` 엔드포인트, BAND Open API v2.2(`post/create`), `urllib`, `unittest`.

설계 출처: `docs/superpowers/specs/2026-06-21-kakao-bookclub-band-summary-design.md`

---

## File Structure

- Modify `scripts/kakao/collector_core.py` — 순수 함수 3개 추가: `filter_after_watermark`, `next_cursor`, `context_window`. (Modal 무의존, 단위 테스트 대상.)
- Modify `tests/test_kakao_collector_core.py` — 위 3개 함수 테스트 클래스 추가.
- Create `scripts/cron/bookclub_state.py` — 워터마크 파일 IO(읽기/쓰기). fetch·post 두 스크립트가 공유.
- Create `tests/test_bookclub_state.py` — 워터마크 라운드트립/누락/손상/기본경로 테스트.
- Create `scripts/cron/kakao_bookclub_fetch.py` — `kakao-messages` 조회 → 워터마크 이후 새 메시지/맥락/cursor/URL을 JSON으로 출력.
- Create `tests/test_kakao_bookclub_fetch.py` — `build_url`/`build_output`/main 가드 테스트.
- Create `scripts/cron/band_post.py` — BAND `post/create` 발신 + 성공 시 워터마크 전진.
- Create `tests/test_band_post.py` — `build_post_payload`/`is_success`/main(성공·env누락) 테스트.
- Modify `scripts/create_modal_secret.py` — `COPY_ENV_KEYS`에 `BAND_ACCESS_TOKEN`, `BAND_KEY` 추가.
- Modify `tests/test_create_modal_secret.py` — BAND 자격증명 복사 테스트 추가.
- Modify `scripts/prepare_runtime.py` — `write_env_file()` 키 목록에 `BAND_ACCESS_TOKEN`, `BAND_KEY` 추가.
- Modify `tests/test_prepare_runtime.py` — BAND 키 기록 테스트 추가.
- Create `cron_jobs/kakao-bookclub-band.json` — Hermes cron job 선언(화·토 07:00 KST).
- Modify `cron_jobs/README.md` — 새 cron job 항목 추가.
- Modify `README.md` — 기능 개요 + BAND 앱등록/토큰 런북 추가.

`modal_app.py`는 변경하지 않는다. 새 cron 스크립트는 `scripts/prepare_runtime.py::sync_cron_scripts_dir()`가 부팅/틱 때 `scripts/cron/` → `~/.hermes/scripts/`로 자동 동기화하므로 동기화 코드 수정도 불필요하다.

---

## Task 1: collector_core 워터마크 순수 함수 + 유닛 테스트

**Files:**
- Modify: `scripts/kakao/collector_core.py`
- Test: `tests/test_kakao_collector_core.py`

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/test_kakao_collector_core.py`의 `ExpiredKeysTests` 클래스 정의 바로 뒤(파일 끝의 `if __name__` 위)에 아래 세 클래스를 추가한다. (파일 상단 import에 `datetime, timedelta, timezone`는 이미 있음.)

```python
class FilterAfterWatermarkTests(unittest.TestCase):
    def setUp(self):
        self.base = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        self.items = [
            {"text": "m1", "received_at": (self.base - timedelta(hours=5)).isoformat()},
            {"text": "m2", "received_at": (self.base - timedelta(hours=3)).isoformat()},
            {"text": "m3", "received_at": (self.base - timedelta(hours=1)).isoformat()},
        ]

    def test_none_cursor_returns_all(self):
        out = collector_core.filter_after_watermark(self.items, None)
        self.assertEqual([r["text"] for r in out], ["m1", "m2", "m3"])

    def test_returns_strictly_newer(self):
        cursor = self.items[1]["received_at"]  # m2 시각
        out = collector_core.filter_after_watermark(self.items, cursor)
        self.assertEqual([r["text"] for r in out], ["m3"])

    def test_empty_when_cursor_at_latest(self):
        cursor = self.items[2]["received_at"]
        self.assertEqual(collector_core.filter_after_watermark(self.items, cursor), [])


class NextCursorTests(unittest.TestCase):
    def test_returns_max_received_at(self):
        base = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        items = [
            {"received_at": (base - timedelta(hours=2)).isoformat()},
            {"received_at": base.isoformat()},
            {"received_at": (base - timedelta(hours=1)).isoformat()},
        ]
        self.assertEqual(collector_core.next_cursor(items), base.isoformat())

    def test_none_when_empty_or_no_timestamps(self):
        self.assertIsNone(collector_core.next_cursor([]))
        self.assertIsNone(collector_core.next_cursor([{"text": "x"}]))


class ContextWindowTests(unittest.TestCase):
    def setUp(self):
        self.base = datetime(2026, 6, 20, 12, 0, tzinfo=timezone.utc)
        self.items = [
            {"text": "old1", "received_at": (self.base - timedelta(hours=5)).isoformat()},
            {"text": "old2", "received_at": (self.base - timedelta(hours=4)).isoformat()},
            {"text": "cursor", "received_at": (self.base - timedelta(hours=3)).isoformat()},
            {"text": "new1", "received_at": (self.base - timedelta(hours=1)).isoformat()},
        ]

    def test_returns_recent_prior_messages_oldest_first(self):
        cursor = self.items[2]["received_at"]
        out = collector_core.context_window(self.items, cursor, 2)
        self.assertEqual([r["text"] for r in out], ["old2", "cursor"])

    def test_empty_when_no_cursor(self):
        self.assertEqual(collector_core.context_window(self.items, None, 3), [])

    def test_empty_when_count_zero(self):
        cursor = self.items[2]["received_at"]
        self.assertEqual(collector_core.context_window(self.items, cursor, 0), [])
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `python -m pytest tests/test_kakao_collector_core.py -v`
Expected: FAIL — `collector_core`에 `filter_after_watermark`/`next_cursor`/`context_window` 없음(AttributeError).

- [ ] **Step 3: 순수 함수 구현**

`scripts/kakao/collector_core.py`의 맨 끝(`expired_keys` 함수 정의 뒤)에 추가한다. 기존 모듈 내 `_parse_received_at` 헬퍼를 재사용한다.

```python
def filter_after_watermark(messages, cursor_iso: str | None) -> list[dict]:
    """Return records strictly newer than `cursor_iso` (by received_at).

    If `cursor_iso` is falsy (first run, no prior summary), returns all records.
    Input order is preserved (the collector returns oldest-first).
    """
    if not cursor_iso:
        return list(messages)
    cutoff = _parse_received_at(cursor_iso)
    return [
        rec for rec in messages
        if _parse_received_at(rec.get("received_at", "")) > cutoff
    ]


def next_cursor(messages) -> str | None:
    """Return the latest received_at string among records, or None if none have one."""
    best = None
    best_dt = None
    for rec in messages:
        value = rec.get("received_at")
        if not value:
            continue
        dt = _parse_received_at(value)
        if best_dt is None or dt > best_dt:
            best_dt, best = dt, value
    return best


def context_window(messages, cursor_iso: str | None, count: int) -> list[dict]:
    """Return up to `count` most-recent records at or before `cursor_iso`.

    These were already covered by a previous summary; the cron prompt uses them
    only as continuity context, not as new content. Returns [] when `cursor_iso`
    is falsy or `count` <= 0. Output is oldest-first.
    """
    if not cursor_iso or count <= 0:
        return []
    cutoff = _parse_received_at(cursor_iso)
    prior = [
        rec for rec in messages
        if _parse_received_at(rec.get("received_at", "")) <= cutoff
    ]
    prior.sort(key=lambda r: r.get("received_at", ""))
    return prior[-count:]
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_kakao_collector_core.py -v`
Expected: PASS (신규 + 기존 모두).

- [ ] **Step 5: 커밋**

```bash
git add scripts/kakao/collector_core.py tests/test_kakao_collector_core.py
git commit -m "$(cat <<'EOF'
북클럽 증분 요약용 워터마크 순수 함수 추가(filter_after_watermark/next_cursor/context_window)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: 워터마크 파일 모듈 `bookclub_state.py` + 유닛 테스트

**Files:**
- Create: `scripts/cron/bookclub_state.py`
- Test: `tests/test_bookclub_state.py`

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/test_bookclub_state.py`:

```python
from pathlib import Path
import importlib.util
import os
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "cron" / "bookclub_state.py"
spec = importlib.util.spec_from_file_location("bookclub_state", MODULE_PATH)
bookclub_state = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bookclub_state)


class WatermarkTests(unittest.TestCase):
    def test_read_returns_none_when_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(bookclub_state.read_cursor(Path(tmp)))

    def test_write_then_read_roundtrip(self):
        with tempfile.TemporaryDirectory() as tmp:
            bookclub_state.write_cursor("아카라카북클럽", "2026-06-21T00:00:00+00:00", Path(tmp))
            self.assertEqual(
                bookclub_state.read_cursor(Path(tmp)),
                "2026-06-21T00:00:00+00:00",
            )

    def test_read_returns_none_on_corrupt_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            bookclub_state.watermark_path(Path(tmp)).write_text("not json", encoding="utf-8")
            self.assertIsNone(bookclub_state.read_cursor(Path(tmp)))

    def test_hermes_home_defaults_to_root(self):
        saved = os.environ.pop("HERMES_HOME", None)
        try:
            self.assertEqual(bookclub_state.hermes_home(), Path("/root/.hermes"))
        finally:
            if saved is not None:
                os.environ["HERMES_HOME"] = saved

    def test_hermes_home_respects_env(self):
        saved = os.environ.get("HERMES_HOME")
        os.environ["HERMES_HOME"] = "/tmp/example-hermes"
        try:
            self.assertEqual(bookclub_state.hermes_home(), Path("/tmp/example-hermes"))
        finally:
            if saved is None:
                os.environ.pop("HERMES_HOME", None)
            else:
                os.environ["HERMES_HOME"] = saved


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python -m pytest tests/test_bookclub_state.py -v`
Expected: FAIL — `bookclub_state.py` 없음(import 실패).

- [ ] **Step 3: 모듈 구현**

`scripts/cron/bookclub_state.py`:

```python
#!/usr/bin/env python3
"""Watermark persistence for the KakaoTalk bookclub → BAND summary cron.

The watermark is the max `received_at` of the last batch successfully posted to
BAND. It is stored as a JSON file under HERMES_HOME (the Hermes volume) so it
survives between the twice-weekly cron runs. `kakao_bookclub_fetch.py` reads it;
`band_post.py` advances it only after a confirmed BAND post.
"""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path

WATERMARK_FILENAME = "kakao_bookclub_watermark.json"


def hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME") or "/root/.hermes")


def watermark_path(home: Path | None = None) -> Path:
    return (home or hermes_home()) / WATERMARK_FILENAME


def read_cursor(home: Path | None = None) -> str | None:
    """Return the stored cursor (ISO8601), or None if no/invalid watermark."""
    try:
        data = json.loads(watermark_path(home).read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError):
        return None
    cursor = data.get("cursor")
    return cursor or None


def write_cursor(room: str, cursor: str, home: Path | None = None) -> None:
    """Persist `cursor` as the new watermark for `room`."""
    path = watermark_path(home)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "room": room,
        "cursor": cursor,
        "updated_at": datetime.now(timezone.utc).isoformat(),
    }
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_bookclub_state.py -v`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add scripts/cron/bookclub_state.py tests/test_bookclub_state.py
git commit -m "$(cat <<'EOF'
북클럽 요약 워터마크 파일 모듈(bookclub_state) 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: 조회 헬퍼 `kakao_bookclub_fetch.py` + 유닛 테스트

**Files:**
- Create: `scripts/cron/kakao_bookclub_fetch.py`
- Test: `tests/test_kakao_bookclub_fetch.py`

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/test_kakao_bookclub_fetch.py`:

```python
from pathlib import Path
import importlib.util
import io
import json
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "cron" / "kakao_bookclub_fetch.py"
spec = importlib.util.spec_from_file_location("kakao_bookclub_fetch", MODULE_PATH)
kakao_bookclub_fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kakao_bookclub_fetch)


class BuildUrlTests(unittest.TestCase):
    def test_includes_token_room_since(self):
        url = kakao_bookclub_fetch.build_url("https://x--kakao-messages.modal.run/", "tok", "14day", "아카라카북클럽")
        self.assertIn("token=tok", url)
        self.assertIn("since=14day", url)
        self.assertIn("room=", url)
        self.assertNotIn(".run//?", url)


class BuildOutputTests(unittest.TestCase):
    def _msg(self, text, hours_ago):
        base = datetime(2026, 6, 21, 0, 0, tzinfo=timezone.utc)
        return {"sender": "a", "text": text, "client_time": "",
                "received_at": (base - timedelta(hours=hours_ago)).isoformat()}

    def test_filters_new_and_collects_urls(self):
        messages = [
            self._msg("옛 메시지 https://old.com", 50),
            self._msg("새 책 추천 https://new.com", 2),
        ]
        cursor = messages[0]["received_at"]
        out = kakao_bookclub_fetch.build_output(messages, cursor)
        self.assertEqual(out["new_count"], 1)
        self.assertEqual(out["new_messages"][0]["text"], "새 책 추천 https://new.com")
        self.assertEqual(out["shared_urls"], ["https://new.com"])
        self.assertEqual(out["cursor"], messages[1]["received_at"])
        self.assertFalse(out["has_more"])

    def test_first_run_includes_all(self):
        messages = [self._msg("첫 실행", 10), self._msg("두번째", 1)]
        out = kakao_bookclub_fetch.build_output(messages, None)
        self.assertEqual(out["new_count"], 2)
        self.assertIsNone(out["watermark_cursor"])

    def test_caps_to_max_new_and_reports_has_more(self):
        messages = [self._msg(f"m{i}", 100 - i) for i in range(5)]
        with mock.patch.object(kakao_bookclub_fetch, "MAX_NEW", 3):
            out = kakao_bookclub_fetch.build_output(messages, None)
        self.assertEqual(out["new_count"], 3)
        self.assertTrue(out["has_more"])
        # cursor는 잘린 3건의 마지막(가장 최근) 것이어야 다음 회차가 이어받는다.
        self.assertEqual(out["cursor"], messages[2]["received_at"])


class MainGuardTests(unittest.TestCase):
    def test_missing_env_prints_ok_false_json_and_returns_1(self):
        buf = io.StringIO()
        with mock.patch.dict(kakao_bookclub_fetch.os.environ, {}, clear=True), \
                mock.patch.object(kakao_bookclub_fetch.sys, "argv", ["kakao_bookclub_fetch.py"]), \
                redirect_stdout(buf):
            rc = kakao_bookclub_fetch.main()
        self.assertEqual(rc, 1)
        payload = json.loads(buf.getvalue())
        self.assertFalse(payload["ok"])
        self.assertIn("not set", payload["error"])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python -m pytest tests/test_kakao_bookclub_fetch.py -v`
Expected: FAIL — `kakao_bookclub_fetch.py` 없음.

- [ ] **Step 3: 헬퍼 구현**

`scripts/cron/kakao_bookclub_fetch.py`:

```python
#!/usr/bin/env python3
"""Fetch new bookclub messages since the last summary and print JSON.

The KakaoTalk bookclub → BAND cron runs this first. It pulls the room's recent
messages from the Modal collector, keeps only those newer than the stored
watermark, and emits what the summary prompt needs: new messages, a small
context window of already-summarized messages, the cursor to advance to on a
successful post, and the deterministically-collected shared URLs.

Reads KAKAO_MESSAGES_URL and KAKAO_COLLECTOR_TOKEN from the environment.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# Make sibling modules (bookclub_state) and the kakao package importable both at
# runtime (~/.hermes/scripts + /opt/hermes-modal/scripts) and in tests (repo).
_HERE = Path(__file__).resolve().parent
for _p in (
    str(_HERE),
    os.environ.get("KAKAO_SCRIPTS_PATH", ""),
    "/opt/hermes-modal/scripts",
    str(_HERE.parent),
):
    if _p and _p not in sys.path:
        sys.path.insert(0, _p)

import bookclub_state  # noqa: E402
from kakao.collector_core import (  # noqa: E402
    context_window,
    extract_urls,
    filter_after_watermark,
    next_cursor,
)

ROOM = "아카라카북클럽"
FETCH_SINCE = "14day"   # collector retention window; the watermark does real filtering
CONTEXT_COUNT = 30      # prior messages passed to the prompt as continuity context only
MAX_NEW = 600           # cap new messages per run; oldest-first so backlog drains over runs


def build_url(base: str, token: str, since: str, room: str) -> str:
    query = {"token": token, "since": since}
    if room:
        query["room"] = room
    return base.rstrip("/") + "?" + urllib.parse.urlencode(query)


def _error_json(error: str, **extra) -> str:
    return json.dumps({"ok": False, "error": error, **extra}, ensure_ascii=False)


def build_output(messages: list, cursor: str | None) -> dict:
    """Assemble the fetch result from collector messages + the stored watermark."""
    new_all = filter_after_watermark(messages, cursor)
    new_messages = new_all[:MAX_NEW]
    return {
        "ok": True,
        "room": ROOM,
        "new_count": len(new_messages),
        "has_more": len(new_all) > len(new_messages),
        "watermark_cursor": cursor,
        "cursor": next_cursor(new_messages),
        "new_messages": new_messages,
        "context_messages": context_window(messages, cursor, CONTEXT_COUNT),
        "shared_urls": extract_urls(new_messages),
    }


def main() -> int:
    argparse.ArgumentParser(description=__doc__).parse_args()

    base = os.environ.get("KAKAO_MESSAGES_URL", "").strip()
    token = os.environ.get("KAKAO_COLLECTOR_TOKEN", "").strip()
    if not base or not token:
        print(_error_json("KAKAO_MESSAGES_URL/KAKAO_COLLECTOR_TOKEN not set"))
        return 1

    url = build_url(base, token, FETCH_SINCE, ROOM)
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace") if exc.fp else ""
        print(_error_json(f"HTTP {exc.code}", body=body))
        return 1
    except Exception as exc:  # noqa: BLE001 - surface any transport failure as JSON
        print(_error_json(str(exc)))
        return 1

    messages = payload.get("messages", [])
    cursor = bookclub_state.read_cursor()
    print(json.dumps(build_output(messages, cursor), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_kakao_bookclub_fetch.py -v`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add scripts/cron/kakao_bookclub_fetch.py tests/test_kakao_bookclub_fetch.py
git commit -m "$(cat <<'EOF'
북클럽 증분 조회 헬퍼(kakao_bookclub_fetch) 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: BAND 발신 헬퍼 `band_post.py` + 유닛 테스트

**Files:**
- Create: `scripts/cron/band_post.py`
- Test: `tests/test_band_post.py`

- [ ] **Step 1: 실패하는 테스트 작성**

`tests/test_band_post.py`:

```python
from pathlib import Path
import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock


MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "cron" / "band_post.py"
spec = importlib.util.spec_from_file_location("band_post", MODULE_PATH)
band_post = importlib.util.module_from_spec(spec)
spec.loader.exec_module(band_post)


class BuildPayloadTests(unittest.TestCase):
    def test_payload_fields(self):
        p = band_post.build_post_payload("tok", "bk", "내용", False)
        self.assertEqual(p["access_token"], "tok")
        self.assertEqual(p["band_key"], "bk")
        self.assertEqual(p["content"], "내용")
        self.assertEqual(p["do_push"], "false")

    def test_do_push_true_serializes_to_string(self):
        p = band_post.build_post_payload("tok", "bk", "내용", True)
        self.assertEqual(p["do_push"], "true")


class IsSuccessTests(unittest.TestCase):
    def test_success_code(self):
        self.assertTrue(band_post.is_success({"result_code": 1}))

    def test_failure_code(self):
        self.assertFalse(band_post.is_success({"result_code": 0}))
        self.assertFalse(band_post.is_success({}))


class MainTests(unittest.TestCase):
    def test_missing_env_returns_1(self):
        buf = io.StringIO()
        with mock.patch.dict(band_post.os.environ, {}, clear=True), \
                mock.patch.object(band_post.sys, "argv", ["band_post.py"]), \
                redirect_stdout(buf):
            rc = band_post.main()
        self.assertEqual(rc, 1)
        self.assertFalse(json.loads(buf.getvalue())["ok"])

    def test_success_posts_and_advances_watermark(self):
        with tempfile.TemporaryDirectory() as tmp:
            content_file = Path(tmp) / "post.txt"
            content_file.write_text("요약 본문", encoding="utf-8")
            env = {"BAND_ACCESS_TOKEN": "tok", "BAND_KEY": "bk", "HERMES_HOME": tmp}
            buf = io.StringIO()
            with mock.patch.dict(band_post.os.environ, env, clear=True), \
                    mock.patch.object(
                        band_post, "post_to_band",
                        return_value={"result_code": 1, "result_data": {"post_key": "777"}}), \
                    mock.patch.object(
                        band_post.sys, "argv",
                        ["band_post.py", "--content-file", str(content_file),
                         "--cursor", "2026-06-21T00:00:00+00:00"]), \
                    redirect_stdout(buf):
                rc = band_post.main()
            self.assertEqual(rc, 0)
            out = json.loads(buf.getvalue())
            self.assertTrue(out["ok"])
            self.assertTrue(out["watermark_advanced"])
            self.assertEqual(out["post_key"], "777")
            watermark = json.loads((Path(tmp) / "kakao_bookclub_watermark.json").read_text(encoding="utf-8"))
            self.assertEqual(watermark["cursor"], "2026-06-21T00:00:00+00:00")

    def test_failure_does_not_advance_watermark(self):
        with tempfile.TemporaryDirectory() as tmp:
            content_file = Path(tmp) / "post.txt"
            content_file.write_text("요약 본문", encoding="utf-8")
            env = {"BAND_ACCESS_TOKEN": "tok", "BAND_KEY": "bk", "HERMES_HOME": tmp}
            buf = io.StringIO()
            with mock.patch.dict(band_post.os.environ, env, clear=True), \
                    mock.patch.object(band_post, "post_to_band", return_value={"result_code": 0}), \
                    mock.patch.object(
                        band_post.sys, "argv",
                        ["band_post.py", "--content-file", str(content_file),
                         "--cursor", "2026-06-21T00:00:00+00:00"]), \
                    redirect_stdout(buf):
                rc = band_post.main()
            self.assertEqual(rc, 1)
            self.assertFalse(json.loads(buf.getvalue())["watermark_advanced"])
            self.assertFalse((Path(tmp) / "kakao_bookclub_watermark.json").exists())


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python -m pytest tests/test_band_post.py -v`
Expected: FAIL — `band_post.py` 없음.

- [ ] **Step 3: 헬퍼 구현**

`scripts/cron/band_post.py`:

```python
#!/usr/bin/env python3
"""Post a composed summary to a Naver BAND band and advance the watermark.

The KakaoTalk bookclub → BAND cron runs this last, passing the composed post
body and the cursor from kakao_bookclub_fetch.py. On a confirmed post
(result_code == 1) it advances the watermark so the next run only summarizes
newer messages. Reads BAND_ACCESS_TOKEN and BAND_KEY from the environment.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import bookclub_state  # noqa: E402

POST_URL = "https://openapi.band.us/v2.2/band/post/create"
DO_PUSH = False  # do not push-notify band members; set True to notify on every post
ROOM = "아카라카북클럽"


def build_post_payload(token: str, band_key: str, content: str, do_push: bool) -> dict:
    return {
        "access_token": token,
        "band_key": band_key,
        "content": content,
        "do_push": "true" if do_push else "false",
    }


def is_success(response: dict) -> bool:
    return response.get("result_code") == 1


def post_to_band(token: str, band_key: str, content: str, do_push: bool) -> dict:
    data = urllib.parse.urlencode(
        build_post_payload(token, band_key, content, do_push)
    ).encode("utf-8")
    req = urllib.request.Request(POST_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _read_content(path: str | None) -> str:
    if path:
        return Path(path).read_text(encoding="utf-8")
    return sys.stdin.read()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--content-file", default="", help="Path to post body; reads stdin if omitted")
    parser.add_argument("--cursor", default="", help="received_at to store as the new watermark on success")
    parser.add_argument("--room", default=ROOM)
    args = parser.parse_args()

    token = os.environ.get("BAND_ACCESS_TOKEN", "").strip()
    band_key = os.environ.get("BAND_KEY", "").strip()
    if not token or not band_key:
        print(json.dumps({"ok": False, "error": "BAND_ACCESS_TOKEN/BAND_KEY not set"}, ensure_ascii=False))
        return 1

    content = _read_content(args.content_file or None).strip()
    if not content:
        print(json.dumps({"ok": False, "error": "empty content"}, ensure_ascii=False))
        return 1

    try:
        response = post_to_band(token, band_key, content, DO_PUSH)
    except Exception as exc:  # noqa: BLE001 - surface any failure as JSON
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        return 1

    ok = is_success(response)
    advanced = False
    if ok and args.cursor:
        bookclub_state.write_cursor(args.room, args.cursor)
        advanced = True

    print(json.dumps({
        "ok": ok,
        "result_code": response.get("result_code"),
        "post_key": (response.get("result_data") or {}).get("post_key"),
        "watermark_advanced": advanced,
    }, ensure_ascii=False))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_band_post.py -v`
Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add scripts/cron/band_post.py tests/test_band_post.py
git commit -m "$(cat <<'EOF'
BAND 발신 헬퍼(band_post) 추가: post/create + 성공 시 워터마크 전진

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: 시크릿/환경변수 배선 (`BAND_ACCESS_TOKEN`, `BAND_KEY`)

**Files:**
- Modify: `scripts/create_modal_secret.py`
- Modify: `scripts/prepare_runtime.py`
- Test: `tests/test_create_modal_secret.py`, `tests/test_prepare_runtime.py`

- [ ] **Step 1: create_modal_secret 실패 테스트 추가**

`tests/test_create_modal_secret.py`의 `KakaoTokenTests` 클래스 뒤(파일 끝 `if __name__` 위)에 추가한다.

```python
class BandCredentialTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self._saved_local_env = cms.LOCAL_ENV
        self._saved_local_auth = cms.LOCAL_AUTH
        cms.LOCAL_ENV = Path(self._tmp.name) / ".env"
        cms.LOCAL_AUTH = Path(self._tmp.name) / "auth.json"
        self._saved = {k: os.environ.pop(k, None) for k in ("BAND_ACCESS_TOKEN", "BAND_KEY")}

    def tearDown(self):
        self._tmp.cleanup()
        cms.LOCAL_ENV = self._saved_local_env
        cms.LOCAL_AUTH = self._saved_local_auth
        for key, value in self._saved.items():
            if value is not None:
                os.environ[key] = value

    def test_band_credentials_copied_from_env(self):
        os.environ["BAND_ACCESS_TOKEN"] = "ZQabc"
        os.environ["BAND_KEY"] = "bandkey123"
        try:
            values = cms.build_secret_values(None)
            self.assertEqual(values["BAND_ACCESS_TOKEN"], "ZQabc")
            self.assertEqual(values["BAND_KEY"], "bandkey123")
        finally:
            os.environ.pop("BAND_ACCESS_TOKEN", None)
            os.environ.pop("BAND_KEY", None)

    def test_band_credentials_absent_when_unset(self):
        values = cms.build_secret_values(None)
        self.assertNotIn("BAND_ACCESS_TOKEN", values)
        self.assertNotIn("BAND_KEY", values)
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `python -m pytest tests/test_create_modal_secret.py -v`
Expected: FAIL — `test_band_credentials_copied_from_env`에서 `values`에 BAND 키 없음(KeyError).

- [ ] **Step 3: create_modal_secret 수정**

`scripts/create_modal_secret.py`의 `COPY_ENV_KEYS` 리스트 끝(`"QMD_RERANK_MODEL",` 다음 줄)에 두 키를 추가한다.

기존:
```python
    "QMD_RERANK_MODEL",
]
# GITHUB_TOKEN is deliberately omitted — see module docstring.
```
변경:
```python
    "QMD_RERANK_MODEL",
    "BAND_ACCESS_TOKEN",
    "BAND_KEY",
]
# GITHUB_TOKEN is deliberately omitted — see module docstring.
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `python -m pytest tests/test_create_modal_secret.py -v`
Expected: PASS.

- [ ] **Step 5: prepare_runtime 실패 테스트 추가**

`tests/test_prepare_runtime.py`의 `PrepareRuntimeTests` 클래스 안, `test_write_env_file_includes_kakao_keys` 메서드 바로 뒤에 추가한다.

```python
    def test_write_env_file_includes_band_keys(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            saved_home = prepare_runtime.HERMES_HOME
            saved = {k: os.environ.get(k) for k in ("BAND_ACCESS_TOKEN", "BAND_KEY")}
            prepare_runtime.HERMES_HOME = Path(tmpdir)
            os.environ["BAND_ACCESS_TOKEN"] = "ZQtoken"
            os.environ["BAND_KEY"] = "bk-9"
            try:
                prepare_runtime.write_env_file()
                content = (Path(tmpdir) / ".env").read_text(encoding="utf-8")
            finally:
                prepare_runtime.HERMES_HOME = saved_home
                for key, value in saved.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value
            self.assertIn('BAND_ACCESS_TOKEN="ZQtoken"', content)
            self.assertIn('BAND_KEY="bk-9"', content)
```

- [ ] **Step 6: 테스트 실패 확인**

Run: `python -m pytest tests/test_prepare_runtime.py::PrepareRuntimeTests::test_write_env_file_includes_band_keys -v`
Expected: FAIL — `write_env_file`이 아직 BAND 키를 기록하지 않음.

- [ ] **Step 7: prepare_runtime 수정**

`scripts/prepare_runtime.py`의 `write_env_file()` 내 `keys` 리스트 끝(`"KAKAO_MESSAGES_URL",` 다음 줄)에 추가한다.

기존:
```python
        "KAKAO_COLLECTOR_TOKEN",
        "KAKAO_MESSAGES_URL",
    ]
```
변경:
```python
        "KAKAO_COLLECTOR_TOKEN",
        "KAKAO_MESSAGES_URL",
        "BAND_ACCESS_TOKEN",
        "BAND_KEY",
    ]
```

- [ ] **Step 8: 테스트 통과 확인**

Run: `python -m pytest tests/test_prepare_runtime.py tests/test_create_modal_secret.py -v`
Expected: PASS (신규 + 기존 모두).

- [ ] **Step 9: 커밋**

```bash
git add scripts/create_modal_secret.py scripts/prepare_runtime.py tests/test_create_modal_secret.py tests/test_prepare_runtime.py
git commit -m "$(cat <<'EOF'
BAND 자격증명(BAND_ACCESS_TOKEN/BAND_KEY) 시크릿·env 배선

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Hermes cron job 정의 + cron_jobs README

**Files:**
- Create: `cron_jobs/kakao-bookclub-band.json`
- Modify: `cron_jobs/README.md`

이 태스크는 선언 파일 작성이라 pytest 대상이 아니다. JSON 유효성만 로컬에서 확인한다.

- [ ] **Step 1: cron job JSON 작성**

`cron_jobs/kakao-bookclub-band.json` (`job_id`는 런타임에 cron job을 만든 뒤 채운다 — Task 8 참조. 그전까지 빈 문자열):

```json
{
  "job_id": "",
  "name": "아카라카북클럽 BAND 주간 요약",
  "schedule": "0 22 * * 1,5",
  "repeat": "forever",
  "deliver": "origin",
  "prompt": "매주 화·토 07:00(KST)에 카카오톡 '아카라카북클럽' 방의 '지난 요약 이후 새 대화'만 요약해 네이버 BAND에 새 글로 게시하고, 결과를 이 텔레그램 대화로 통지한다.\n\n절차:\n1) 다음 명령으로 새 메시지를 가져온다(JSON이 stdout으로 출력됨):\n   python /root/.hermes/scripts/kakao_bookclub_fetch.py\n   - 출력 필드: ok(bool), room, new_count(int), has_more(bool), watermark_cursor, cursor(이번에 성공 게시하면 전진시킬 값), new_messages[](sender/text/client_time/received_at), context_messages[](이미 요약된 직전 맥락), shared_urls[].\n2) ok가 false면 BAND에 게시하지 말고, 오류 내용을 텔레그램으로 보고하고 종료한다.\n3) new_count가 0이면 BAND에 게시하지 말고 '이번 회차에 새 대화가 없습니다'라고만 텔레그램에 보고하고 종료한다.\n4) new_messages만 요약 대상으로 삼는다. context_messages는 앞 주제와의 연결을 이해하기 위한 '맥락'일 뿐이며 다시 요약하지 않는다. new_messages·context_messages·shared_urls에 실제로 있는 내용만 사용하고, 없는 사실은 지어내지 않는다.\n5) BAND 본문을 '평문'으로 작성한다(마크다운 #/** 금지. BAND는 URL을 자동 링크로 보여줌). 다음 구조를 따른다(해당 항목이 없으면 그 섹션은 생략):\n   - 첫 줄 제목: 📖 아카라카북클럽 대화 요약\n   - 둘째 줄 기준: 기준: 지난 요약 이후 새 대화 {new_count}건 (has_more가 true면 '(이번 회차 분량 제한으로 일부는 다음 요약에 포함)'을 덧붙임)\n   - 📅 향후 행사 일정/주제: 모임/행사 날짜·주제. 날짜/시각은 대화에 있는 것만, 없으면 지어내지 말 것.\n   - 📚 추천 책: 각 항목을 '『제목』 — 저자' 형식으로. 대화에 저자가 없으면 웹검색으로 저자를 확인해 채운다(web 도구 사용). 검색으로도 확정 못 하면 '저자 미상'으로 적고 지어내지 않는다.\n   - 🔗 공유된 링크: shared_urls의 각 URL을 web 도구로 방문해 페이지 제목을 확인하고 '제목 — URL' 형식으로 적는다. 방문이 안 되면 제목 없이 URL만 적는다. shared_urls에 없는 링크는 만들지 않는다.\n   - 🗣️ 주요 대화주제와 참여자: 핵심 화제 3~6개를, 누가(보낸이) 무엇을 말했는지 위주로. 결정/약속(다음 모임, 읽을 범위 등)은 강조.\n   - 일부 메시지가 누락됐을 수 있으니 단정적 표현은 피한다.\n6) 작성한 본문을 파일로 저장한 뒤 BAND에 게시한다. 1번 출력의 cursor 값을 그대로 --cursor에 넘긴다(게시 성공 시 워터마크가 전진해 다음 회차가 그 이후만 요약한다):\n   - 본문을 /tmp/band_post_body.txt 에 저장(터미널로 파일 작성).\n   - python /root/.hermes/scripts/band_post.py --content-file /tmp/band_post_body.txt --cursor '<1번 출력의 cursor>' --room 아카라카북클럽\n   - band_post.py 출력 JSON에서 ok/result_code/post_key/watermark_advanced를 확인한다.\n7) 텔레그램으로 결과를 한국어로 간결히 보고한다: 게시 성공/실패(post_key 포함 여부), 워터마크 전진 여부, 그리고 BAND 본문의 앞부분 미리보기. 실패면 band_post.py가 출력한 error도 함께 보고한다.\n\n주의:\n- 새 메시지(new_messages)에 근거하지 않은 내용은 절대 지어내지 않는다.\n- 사진/스티커는 [사진] 등 텍스트로만 들어올 수 있으니 텍스트 위주로 요약한다.\n- 이 cron 작업 안에서 새 cronjob을 만들거나 기존 cronjob을 수정하지 않는다.",
  "skills": [],
  "model": null,
  "provider": null,
  "base_url": null,
  "script": null,
  "context_from": null,
  "enabled_toolsets": [
    "web",
    "terminal"
  ],
  "workdir": null,
  "enabled": true,
  "state": "scheduled",
  "notes": "Tracked source for Hermes cron job. job_id는 런타임 생성 후 채운다. Runtime-only fields(origin chat_id, next_run_at, last_run_at, last_status, delivery errors, output paths)는 커밋하지 않는다."
}
```

- [ ] **Step 2: JSON 유효성 확인**

Run: `python -c "import json; json.load(open('cron_jobs/kakao-bookclub-band.json', encoding='utf-8')); print('valid')"`
Expected: `valid`.

- [ ] **Step 3: cron_jobs/README.md 항목 추가**

`cron_jobs/README.md`의 "## Current jobs" 목록 끝(`weekly-git-activity.json` 항목 뒤)에 추가한다.

```markdown
- `kakao-bookclub-band.json`: 카카오톡 '아카라카북클럽' 방의 증분 대화를 요약해 네이버 BAND에 게시.
  - Schedule: `0 22 * * 1,5` in Hermes cron state, which corresponds to **화·토 07:00 KST**.
  - Toolsets: `web`, `terminal`. 책 저자 웹검색·URL 제목 방문이 필요하다.
  - Scripts: `kakao_bookclub_fetch.py`(증분 조회), `band_post.py`(BAND 발신), 공유 모듈 `bookclub_state.py`.
    셋 다 `scripts/cron/`에서 `~/.hermes/scripts/`로 `prepare_runtime.py`가 동기화한다.
  - 증분 상태: `~/.hermes/kakao_bookclub_watermark.json`(BAND 게시 성공 후에만 전진).
  - 시크릿: `BAND_ACCESS_TOKEN`, `BAND_KEY`(`scripts/create_modal_secret.py`로 반영). BAND 앱등록/토큰
    발급은 최상위 `README.md`의 런북 참조. do_push는 `band_post.py`에서 기본 false.
```

- [ ] **Step 4: 커밋**

```bash
git add cron_jobs/kakao-bookclub-band.json cron_jobs/README.md
git commit -m "$(cat <<'EOF'
북클럽 BAND 주간 요약 cron job 정의 추가(화·토 07:00 KST)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: README 기능 개요 + BAND 앱등록/토큰 런북

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README에 섹션 추가**

`README.md` 끝에 다음 섹션을 추가한다.

```markdown
## 카카오톡 북클럽 → BAND 주간 요약 (cron)

매주 **화·토 07:00 KST**에 카카오톡 '아카라카북클럽' 방의 **지난 요약 이후 새 대화**만 요약해
네이버 **BAND**(https://www.band.us/band/102765569/post)에 새 글로 게시하고, 결과를 텔레그램으로
통지한다. 설계: `docs/superpowers/specs/2026-06-21-kakao-bookclub-band-summary-design.md`.

구성(기존 수집 파이프라인 재사용):
- 수집: 안드로이드 수집앱 → Modal `kakao-ingest` → `modal.Dict`(14일). 변경 없음.
  **전제**: 수집앱 대상 방 목록에 '아카라카북클럽'이 있어 실제 수집 중이어야 한다.
- 요약·게시: Hermes cron job `아카라카북클럽 BAND 주간 요약`(web+terminal). `cron_tick`(매일 07:00·19:00
  KST)이 깨운 컨테이너에서 `hermes cron tick`으로 실행된다.
  - `kakao_bookclub_fetch.py`: `kakao-messages`에서 받아 워터마크 이후 새 메시지/맥락/cursor/URL 추출.
  - 에이전트: 행사/책(+저자 웹검색)/URL(제목 방문)/주제·참여자로 평문 본문 작성.
  - `band_post.py`: BAND `post/create`로 게시(do_push 기본 false). 성공 시에만 워터마크 전진.
- 증분 상태: `~/.hermes/kakao_bookclub_watermark.json`(Hermes 볼륨, 게시 성공 후 전진).

### BAND 앱등록 / 토큰 발급 (최초 1회)
1. https://developers.band.us/develop/myapps/list 에서 앱 생성.
   - **Redirect URI**: `http://localhost:8080/` (콘솔은 "도메인"을 받으며, 서버 cron은 실행 시점에
     콜백이 필요 없다. BAND 공식 샘플이 쓰는 값).
2. 앱의 **Access Token** 섹션에서 **"밴드 계정 연동"** 버튼으로 본인 계정 토큰을 발급(콘솔에 바로 표시).
   토큰 수명 ≈ 10년이라 cron에서 갱신 불필요.
3. 대상 밴드의 `band_key` 확인(최초 1회):
   ```bash
   curl -s "https://openapi.band.us/v2.1/bands?access_token=<발급토큰>"
   ```
   결과 `result_data.bands[]`에서 이름이 맞는 밴드의 `band_key`를 고른다(URL의 `102765569`와는 다른 값).
4. 자격증명을 `~/.hermes/.env`에 넣고 시크릿에 반영:
   ```bash
   # ~/.hermes/.env 에 BAND_ACCESS_TOKEN=..., BAND_KEY=... 추가 후
   python scripts/create_modal_secret.py
   modal deploy modal_app.py
   ```

### cron job 등록 / 테스트
1. 배포된 런타임에서 cron job을 1회 생성하고(`cron_jobs/kakao-bookclub-band.json`의 필드대로),
   생성된 `job_id`를 그 JSON에 채워 커밋한다(`scripts/apply_cron_jobs.py`로 이후 동기화).
2. 단발 실행으로 확인: `hermes cron run <job_id>` → BAND에 글 게시 + 텔레그램 통지 확인.
3. 같은 명령을 한 번 더 실행 → `new_count:0`(증분 동작) 확인. 빈 구간이면 게시가 생략된다.
```

- [ ] **Step 2: 전체 테스트 실행**

Run: `python -m pytest tests/ -v`
Expected: 모든 테스트 PASS.

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "$(cat <<'EOF'
북클럽 BAND 요약 기능 README + BAND 앱등록/토큰 런북 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 배포 + 수동 E2E 런북 (코드 변경 없음)

이 태스크는 배포/실행 검증이다. 새 커밋은 cron job `job_id` 반영(Step 5)만 발생한다.

- [ ] **Step 1: BAND 앱등록 + 토큰/밴드키 확보**

Task 7의 "BAND 앱등록 / 토큰 발급" 1~3을 수행해 `BAND_ACCESS_TOKEN`, `BAND_KEY`를 확보한다.

- [ ] **Step 2: 시크릿 반영 + 배포**

```bash
# ~/.hermes/.env 에 BAND_ACCESS_TOKEN/BAND_KEY 추가 후
python scripts/create_modal_secret.py        # 출력 키 목록에 BAND_ACCESS_TOKEN/BAND_KEY 포함 확인
modal deploy modal_app.py
```
Expected: 시크릿 키 목록에 두 키 포함, 배포 성공.

- [ ] **Step 3: 헬퍼 단독 검증(런타임)**

```bash
modal run modal_app.py::doctor   # 런타임 기동 확인(선택)
```
배포된 컨테이너에서 fetch가 동작하는지 보려면 Step 4의 단발 cron 실행으로 확인한다(헬퍼는 런타임 env에
의존하므로 로컬 단독 실행보다 cron 경로 검증이 정확하다).

- [ ] **Step 4: cron job 생성 + 단발 실행**

런타임에서 `cron_jobs/kakao-bookclub-band.json`의 필드대로 cron job을 생성한다(name/schedule
`0 22 * * 1,5`/deliver origin/toolsets web,terminal/prompt 동일). 생성 후:

```bash
hermes cron list                 # 생성된 job_id 확인
hermes cron run <job_id>         # 단발 실행
```
Expected:
- BAND(https://www.band.us/band/102765569/post)에 요약 글이 새로 올라온다(do_push 없음).
- 텔레그램으로 게시 성공/미리보기 통지가 온다.
- `~/.hermes/kakao_bookclub_watermark.json`이 생성/갱신된다.

- [ ] **Step 5: job_id 반영 커밋**

생성된 `job_id`를 `cron_jobs/kakao-bookclub-band.json`에 채운다.

```bash
python scripts/apply_cron_jobs.py --dry-run   # 정의 ↔ 런타임 차이 확인
git add cron_jobs/kakao-bookclub-band.json
git commit -m "$(cat <<'EOF'
북클럽 BAND 요약 cron job_id 반영

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: 증분 동작 확인**

```bash
hermes cron run <job_id>         # 두 번째 단발 실행
```
Expected: 새 대화가 없으면 fetch가 `new_count:0` → **BAND 게시 생략**, 텔레그램에 "새 대화가 없습니다"만.

- [ ] **Step 7: 화·토 자동 실행 확인(다음 주기)**

다음 화/토 07:00 KST 이후 BAND에 글이 올라오고 텔레그램 통지가 왔는지, `hermes cron list`의
`last_status`가 정상인지 확인한다.

---

## Self-Review (작성자 점검 결과)

**Spec 커버리지**:
- §1 목표(화·토 07:00 KST / 증분 / 행사·책+저자·URL제목·주제참여자 / 텔레그램 통지 / do_push=false) →
  Task 6 cron JSON(schedule·prompt), Task 3(증분 fetch), Task 4(do_push=False).
- §3 구조(cron_tick 재사용, 새 엔드포인트 없음) → Task 6, modal_app.py 무변경(File Structure 명시).
- §4.1 watermark 볼륨 파일 → Task 2 `bookclub_state`, Task 4 전진.
- §4.2 순수 함수 3개 → Task 1.
- §4.3 fetch 헬퍼(JSON 출력/context/cursor/URL) → Task 3.
- §4.4 band_post(payload/result_code 확인/전진) → Task 4.
- §4.5 cron job(schedule·toolsets·prompt) → Task 6.
- §4.6 시크릿/env 배선(COPY_ENV_KEYS·write_env_file) → Task 5.
- §5 BAND 앱등록/Redirect URI/토큰/band_key → Task 7 런북, Task 8 실행.
- §7 엣지(새 대화 없음 게시 생략·실패 시 미전진·첫 실행 전체·저자미상·URL제목실패) →
  Task 6 prompt(3·5), Task 4(미전진 테스트), Task 1(None cursor=전체).
- §8 테스트 계획 → 각 Task의 pytest + Task 8 수동 E2E.

**Placeholder 스캔**: 코드 스텝은 모두 완전한 코드 포함. cron JSON의 `job_id: ""`는 런타임 생성 후
채우는 실제 운영값(Task 8 Step 5)으로, 구현 공백이 아님.

**타입/이름 일관성**:
- `filter_after_watermark/next_cursor/context_window`(Task 1) ↔ `kakao_bookclub_fetch` import(Task 3) 일치.
- `read_cursor/write_cursor/watermark_path/hermes_home`(Task 2) ↔ fetch(read_cursor)·band_post(write_cursor) 일치.
- `build_url/build_output`(Task 3), `build_post_payload/is_success/post_to_band`(Task 4) ↔ 각 테스트 일치.
- fetch 출력 필드 `new_messages/context_messages/cursor/shared_urls/new_count/has_more` ↔ cron prompt(Task 6)
  가 참조하는 필드명 일치.
- 워터마크 파일명 `kakao_bookclub_watermark.json` 전 구간 일치(Task 2 상수, Task 4 테스트, 설계 §4.1).
- env 키 `BAND_ACCESS_TOKEN/BAND_KEY`(Task 4·5), `KAKAO_MESSAGES_URL/KAKAO_COLLECTOR_TOKEN`(Task 3) 일치.
```
