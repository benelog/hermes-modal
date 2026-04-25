#!/usr/bin/env python3
"""Mirror HERMES_HOME/skills/ into the hermes-modal git repo and push.

Idempotent and concurrent-safe: an exclusive file lock serializes overlapping
runs, and a SHA256 fingerprint stored alongside the skills makes the no-change
fast path free.
"""

from __future__ import annotations

import fcntl
import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_URL = "https://github.com/benelog/hermes-modal.git"
REPO_BRANCH = "main"
GIT_USER_NAME = "Hermes Modal Bot"
GIT_USER_EMAIL = "hermes-modal-bot@users.noreply.github.com"

HERMES_HOME = Path(os.environ.get("HERMES_HOME", "/root/.hermes"))
SKILLS_DIR = HERMES_HOME / "skills"
FINGERPRINT_FILE = SKILLS_DIR / ".last_committed_hash"
LOCK_FILE = Path("/tmp/hermes-modal-commit-skills.lock")
WORK_DIR = Path("/tmp/hermes-modal-clone")


def _is_committable(rel: Path) -> bool:
    return not any(part.startswith(".") for part in rel.parts)


def fingerprint() -> str:
    if not SKILLS_DIR.exists():
        return ""
    h = hashlib.sha256()
    files = sorted(
        f for f in SKILLS_DIR.rglob("*")
        if f.is_file() and _is_committable(f.relative_to(SKILLS_DIR))
    )
    for f in files:
        rel = f.relative_to(SKILLS_DIR).as_posix()
        h.update(rel.encode("utf-8"))
        h.update(b"\0")
        h.update(f.read_bytes())
        h.update(b"\0")
    return h.hexdigest()


def _git(args: list[str], cwd: Path, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(cwd), *args], check=True, text=True, **kw)


def _push_with_retry(work: Path) -> None:
    try:
        _git(["push", "origin", REPO_BRANCH], work)
        return
    except subprocess.CalledProcessError:
        pass
    _git(["fetch", "origin", REPO_BRANCH], work)
    _git(["rebase", f"origin/{REPO_BRANCH}"], work)
    _git(["push", "origin", REPO_BRANCH], work)


def commit_skills() -> int:
    if not (SKILLS_DIR / ".image-manifest.txt").exists():
        print("skipped: skills manifest missing — has prepare_runtime.py run?")
        return 0

    current = fingerprint()
    last = FINGERPRINT_FILE.read_text(encoding="utf-8").strip() if FINGERPRINT_FILE.exists() else ""
    if current and current == last:
        return 0

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print("skipped: GITHUB_TOKEN not set")
        return 0

    slug = REPO_URL.removeprefix("https://github.com/").removesuffix(".git")
    auth_url = f"https://x-access-token:{token}@github.com/{slug}.git"

    if WORK_DIR.exists():
        shutil.rmtree(WORK_DIR)
    subprocess.run(
        ["git", "clone", "--depth", "1", "--branch", REPO_BRANCH, auth_url, str(WORK_DIR)],
        check=True,
    )
    _git(["config", "user.email", GIT_USER_EMAIL], WORK_DIR)
    _git(["config", "user.name", GIT_USER_NAME], WORK_DIR)

    dst = WORK_DIR / "scripts" / "skills"
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    for f in SKILLS_DIR.rglob("*"):
        if not f.is_file():
            continue
        rel = f.relative_to(SKILLS_DIR)
        if not _is_committable(rel):
            continue
        target = dst / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(str(f), str(target))

    diff = _git(["status", "--porcelain", "scripts/skills/"], WORK_DIR, capture_output=True)
    if not diff.stdout.strip():
        FINGERPRINT_FILE.write_text(current + "\n", encoding="utf-8")
        return 0

    _git(["add", "scripts/skills/"], WORK_DIR)
    _git(["commit", "-m", "Sync skills from Modal volume"], WORK_DIR)
    _push_with_retry(WORK_DIR)
    FINGERPRINT_FILE.write_text(current + "\n", encoding="utf-8")
    print(f"pushed:\n{diff.stdout}")
    return 0


def main() -> int:
    LOCK_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(LOCK_FILE, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return commit_skills()


if __name__ == "__main__":
    sys.exit(main())
