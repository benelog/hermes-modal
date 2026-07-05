#!/usr/bin/env python3
"""PostToolUse(Edit|Write): run the server unit tests when server-side Python
changes, so regressions surface immediately instead of at commit time.

Scope: modal_app.py, scripts/**/*.py, tests/**. Android (gradle) tests are too
slow for a per-edit hook; run those explicitly via install.sh testDebugUnitTest.
"""
import json
import os
import subprocess
import sys

data = json.load(sys.stdin)
fp = (data.get("tool_input") or {}).get("file_path", "")
proj = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
try:
    rel = os.path.relpath(fp, proj)
except ValueError:
    sys.exit(0)

watched = (
    rel == "modal_app.py"
    or (rel.startswith("scripts" + os.sep) and rel.endswith(".py"))
    or rel.startswith("tests" + os.sep)
)
if not watched or rel.startswith(".claude"):
    sys.exit(0)

try:
    proc = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests"],
        cwd=proj, capture_output=True, text=True, timeout=180,
    )
except Exception:
    sys.exit(0)

if proc.returncode != 0:
    tail = "\n".join((proc.stderr or proc.stdout).splitlines()[-30:])
    print(f"Server unit tests FAILED after editing {rel}:\n{tail}", file=sys.stderr)
    sys.exit(2)
