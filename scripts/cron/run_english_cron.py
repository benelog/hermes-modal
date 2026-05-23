#!/usr/bin/env python3
"""Trigger the English-learning Hermes cron job from a Telegram quick command."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

JOB_ID = "157db4a4b6c3"
LOG_PATH = Path.home() / ".hermes" / "logs" / "english_quick_command.log"

LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
cmd = [
    "/bin/sh",
    "-lc",
    f"hermes cron run {JOB_ID} --accept-hooks && hermes cron tick --accept-hooks",
]
with LOG_PATH.open("ab") as log:
    subprocess.Popen(
        cmd,
        stdout=log,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
    )

sys.stdout.write("영어학습 cronjob 실행을 요청했습니다. 완료되면 이 채팅으로 결과가 전달됩니다.")
