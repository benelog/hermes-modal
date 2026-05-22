#!/usr/bin/env python3
"""Collect 5 random English expressions from benelog/english docs.

The script keeps a local shallow clone of benelog/english and runs git pull on
every execution so the cron job samples from the latest docs/main content.
"""
from __future__ import annotations

import datetime as _dt
import json
import os
import random
import re
import shutil
import subprocess
from pathlib import Path

REPO_URL = "https://github.com/benelog/english.git"
REPO_BRANCH = "main"
SOURCE_REPO = "https://github.com/benelog/english/tree/main/docs"
SITE_BASE = "https://english.benelog.net/"
CACHE_ROOT = Path(os.environ.get("HERMES_ENGLISH_CACHE_DIR", "/root/.cache/hermes/english"))
REPO_DIR = CACHE_ROOT / "repo"
DOCS_DIR = REPO_DIR / "docs"


def run(cmd: list[str], cwd: Path | None = None) -> None:
    subprocess.run(cmd, cwd=str(cwd) if cwd else None, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def sync_repo() -> None:
    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    if (REPO_DIR / ".git").exists():
        run(["git", "fetch", "--prune", "origin"], cwd=REPO_DIR)
        run(["git", "checkout", REPO_BRANCH], cwd=REPO_DIR)
        run(["git", "pull", "--ff-only", "origin", REPO_BRANCH], cwd=REPO_DIR)
    else:
        if REPO_DIR.exists():
            shutil.rmtree(REPO_DIR)
        run(["git", "clone", "--branch", REPO_BRANCH, "--depth", "1", REPO_URL, str(REPO_DIR)])


def page_url(path: Path) -> str:
    rel = path.relative_to(DOCS_DIR).as_posix()
    if rel == "index.md":
        return SITE_BASE
    if rel.lower().endswith(".md"):
        rel = rel[:-3]
    return SITE_BASE + rel + ".html"


def clean(s: str) -> str:
    s = s.strip()
    s = re.sub(r"<!--.*?-->", "", s)
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def extract_items(path: Path, text: str):
    lines = text.splitlines()
    title = None
    current_heading = None
    items = []
    i = 0
    while i < len(lines):
        raw = lines[i].rstrip()
        stripped = raw.strip()
        if stripped.startswith("#"):
            heading = stripped.lstrip("#").strip()
            if title is None:
                title = heading
            current_heading = heading
            i += 1
            continue
        # Top-level markdown bullet only; nested bullets become examples/notes.
        if re.match(r"^\*\s+\S", raw):
            expr = clean(raw[1:].strip())
            if expr and not expr.startswith("!") and not expr.startswith("http"):
                notes = []
                j = i + 1
                while j < len(lines):
                    nxt = lines[j].rstrip()
                    if re.match(r"^\*\s+\S", nxt) or re.match(r"^#{1,6}\s+", nxt.strip()):
                        break
                    m = re.match(r"^\s{2,}[*-]\s+(.+)", nxt)
                    if m:
                        note = clean(m.group(1))
                        if note and not note.startswith("!"):
                            notes.append(note)
                    j += 1
                items.append({
                    "expression": expr,
                    "notes": notes[:3],
                    "page_title": title or current_heading or path.stem,
                    "section": current_heading,
                    "source_file": path.relative_to(DOCS_DIR).as_posix(),
                    "url": page_url(path),
                })
            i += 1
            continue
        i += 1
    return items


def main() -> int:
    sync_repo()
    all_items = []
    for path in sorted(DOCS_DIR.rglob("*.md")):
        rel = path.relative_to(DOCS_DIR).as_posix()
        if rel == "index.md" or rel.startswith("lyrics/"):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        all_items.extend(extract_items(path, text))
    pool = [x for x in all_items if x.get("notes")] or all_items
    if len(pool) < 5:
        raise SystemExit(f"Only {len(pool)} expressions found")
    chosen = random.SystemRandom().sample(pool, 5)
    payload = {
        "generated_at_kst": _dt.datetime.now(_dt.timezone(_dt.timedelta(hours=9))).isoformat(timespec="seconds"),
        "source_repo": SOURCE_REPO,
        "site": SITE_BASE,
        "repo_branch": REPO_BRANCH,
        "repo_cache": str(REPO_DIR),
        "git_pull_each_run": True,
        "count_available": len(all_items),
        "items": chosen,
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
