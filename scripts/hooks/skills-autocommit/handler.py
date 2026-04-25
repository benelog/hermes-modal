"""Spawn commit_skills.py in the background when an agent turn ends.

The script no-ops fast when the skills fingerprint is unchanged, so firing this
on every turn is cheap.
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

SCRIPT = Path("/opt/hermes-modal/scripts/commit_skills.py")
LOG = Path(os.environ.get("HERMES_HOME", "/root/.hermes")) / "logs" / "skills-autocommit.log"


def handle(event_type: str, context: dict):
    if not SCRIPT.exists():
        return
    LOG.parent.mkdir(parents=True, exist_ok=True)
    log_fp = open(LOG, "a")
    subprocess.Popen(
        [sys.executable, str(SCRIPT)],
        stdout=log_fp,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
