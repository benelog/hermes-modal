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
