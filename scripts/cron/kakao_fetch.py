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
import urllib.error
import urllib.parse
import urllib.request


def build_url(base: str, token: str, since: str, room: str) -> str:
    query = {"token": token, "since": since}
    if room:
        query["room"] = room
    return base.rstrip("/") + "?" + urllib.parse.urlencode(query)


def _error_json(error: str, **extra) -> str:
    return json.dumps({"ok": False, "error": error, **extra}, ensure_ascii=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--since", default="1day")
    parser.add_argument("--room", default="")
    args = parser.parse_args()

    base = os.environ.get("KAKAO_MESSAGES_URL", "").strip()
    token = os.environ.get("KAKAO_COLLECTOR_TOKEN", "").strip()
    if not base or not token:
        print(_error_json("KAKAO_MESSAGES_URL/KAKAO_COLLECTOR_TOKEN not set"))
        return 1

    url = build_url(base, token, args.since, args.room)
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            data = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        # Surface the collector's structured reason (e.g. 401 token mismatch)
        # instead of a bare "HTTP Error 401" string, so the skill can act on it.
        body = exc.read().decode("utf-8", "replace") if exc.fp else ""
        print(_error_json(f"HTTP {exc.code}", body=body))
        return 1
    except Exception as exc:  # noqa: BLE001 - surface any transport failure as JSON
        print(_error_json(str(exc)))
        return 1

    # Guarantee the "always prints JSON" contract: validate before passing the
    # body through unchanged, falling into the error envelope on non-JSON.
    try:
        json.loads(data)
    except ValueError:
        print(_error_json("collector returned non-JSON response", body=data[:500]))
        return 1

    sys.stdout.write(data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
