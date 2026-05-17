#!/usr/bin/env python3
"""Apply git-tracked Hermes cron job definitions to the local Hermes state.

This script intentionally syncs only declarative fields from cron_jobs/*.json.
Runtime fields such as origin chat IDs, last_run_at, next_run_at, and delivery
errors are preserved by the Hermes cron storage layer.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _load_cron_module():
    try:
        from cron.jobs import get_job, update_job, parse_schedule  # type: ignore
    except Exception as exc:  # pragma: no cover - depends on Hermes runtime
        raise SystemExit(f"Could not import Hermes cron module: {exc}")
    return get_job, update_job, parse_schedule


def _definition_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    return sorted(path.glob("*.json"))


def _updates_from_definition(defn: dict[str, Any], parse_schedule) -> dict[str, Any]:
    schedule = defn.get("schedule")
    if not schedule:
        raise ValueError("definition is missing required field: schedule")

    updates: dict[str, Any] = {
        "name": defn.get("name"),
        "prompt": defn.get("prompt"),
        "schedule": parse_schedule(str(schedule)),
        "schedule_display": str(schedule),
        "deliver": defn.get("deliver", "origin"),
        "skills": defn.get("skills", []),
        "skill": (defn.get("skills") or [None])[0],
        "model": defn.get("model"),
        "provider": defn.get("provider"),
        "base_url": defn.get("base_url"),
        "script": defn.get("script"),
        "context_from": defn.get("context_from"),
        "enabled_toolsets": defn.get("enabled_toolsets"),
        "workdir": defn.get("workdir"),
        "enabled": defn.get("enabled", True),
        "state": defn.get("state", "scheduled"),
    }
    # Do not sync the runtime `repeat.completed` counter from Git.  The tracked
    # definition keeps `repeat` as documentation, while Hermes preserves actual
    # run counters in ~/.hermes/cron/jobs.json.

    return updates


def apply_definition(path: Path, dry_run: bool) -> bool:
    get_job, update_job, parse_schedule = _load_cron_module()
    defn = json.loads(path.read_text(encoding="utf-8"))
    job_id = defn.get("job_id")
    if not job_id:
        raise ValueError(f"{path}: missing required field: job_id")
    current = get_job(job_id)
    if not current:
        raise ValueError(f"{path}: Hermes cron job {job_id!r} does not exist; create it first, then apply this definition")

    updates = _updates_from_definition(defn, parse_schedule)
    changed = any(current.get(k) != v for k, v in updates.items())
    if dry_run:
        print(f"{path}: {'would update' if changed else 'already up to date'} ({job_id})")
        return changed
    updated = update_job(job_id, updates)
    if not updated:
        raise ValueError(f"{path}: update_job returned no result for {job_id!r}")
    print(f"{path}: {'updated' if changed else 'confirmed'} ({job_id})")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description="Apply git-tracked Hermes cron job definitions")
    parser.add_argument("path", nargs="?", default=str(_repo_root() / "cron_jobs"), help="JSON file or directory of JSON definitions")
    parser.add_argument("--dry-run", action="store_true", help="Show whether definitions differ without writing Hermes cron state")
    args = parser.parse_args()

    target = Path(args.path)
    if not target.is_absolute():
        target = (_repo_root() / target).resolve()
    files = _definition_files(target)
    if not files:
        print(f"No cron job definitions found at {target}", file=sys.stderr)
        return 1
    for file in files:
        apply_definition(file, args.dry_run)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
