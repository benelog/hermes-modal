#!/usr/bin/env python3
"""Check known top-level article dates in wiki/content/ai-trend.md are descending per section.

Usage:
  python scripts/check-ai-trend-sort.py /root/workspace/benelog/wiki/content/ai-trend.md

Unresolved-date top-level bullets are reported but ignored for the descending-date check; by convention they should be placed after dated items in their section.
"""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path

DATE_RE = re.compile(r"(20\d\d)[.-](\d{1,2})[.-](\d{1,2})")
SECTION_RE = re.compile(r"^##\s+(.+?)\s*$", re.M)


def parse_date(line: str) -> dt.date | None:
    m = DATE_RE.search(line)
    if not m:
        return None
    return dt.date(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def iter_sections(text: str):
    matches = list(SECTION_RE.finditer(text))
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        yield m.group(1), text[start:end]


def main(path: str) -> int:
    text = Path(path).read_text()
    failed = False
    for name, body in iter_sections(text):
        entries = []
        for line in body.splitlines():
            if line.startswith("* "):
                entries.append((parse_date(line), line))
        dated = [(d, line) for d, line in entries if d]
        unresolved = [line for d, line in entries if d is None]
        ok = all(dated[i][0] >= dated[i + 1][0] for i in range(len(dated) - 1))
        print(f"{name}: known-date-desc={ok}, dated={len(dated)}, unresolved={len(unresolved)}")
        if unresolved:
            for line in unresolved:
                print(f"  unresolved: {line[:120]}")
        if not ok:
            failed = True
            for d, line in dated:
                print(f"  {d}: {line[:120]}")
    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1]))
