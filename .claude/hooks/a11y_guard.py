#!/usr/bin/env python3
"""PostToolUse(Bash): after installing the collector app, verify the
accessibility service is still bound.

A package update force-stops the app and Android silently disables the
accessibility service -> collection stops with no error anywhere. This hook
catches the forgotten `enable_service.sh` immediately.
"""
import json
import os
import subprocess
import sys
import time

data = json.load(sys.stdin)
cmd = (data.get("tool_input") or {}).get("command", "")
if "installDebug" not in cmd and "adb install" not in cmd:
    sys.exit(0)

adb = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
if not os.path.exists(adb):
    sys.exit(0)
try:
    state = subprocess.run([adb, "get-state"], capture_output=True, text=True, timeout=10)
except Exception:
    sys.exit(0)
if state.stdout.strip() != "device":
    sys.exit(0)

def bound() -> bool:
    try:
        out = subprocess.run(
            [adb, "shell", "dumpsys", "accessibility"],
            capture_output=True, text=True, timeout=15,
        ).stdout
    except Exception:
        return True  # can't verify -> don't block
    return "label=Kakao Collector" in out

# Rebind can lag a few seconds after enable_service.sh; check twice.
if bound():
    sys.exit(0)
time.sleep(4)
if bound():
    sys.exit(0)

print(
    "Kakao Collector accessibility service is UNBOUND after this install "
    "(package update force-stops the app and Android drops the service). "
    "Run kakao-collector/enable_service.sh, then verify: "
    "adb shell dumpsys accessibility | grep 'label=Kakao Collector'",
    file=sys.stderr,
)
sys.exit(2)
