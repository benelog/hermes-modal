# Git-tracked Hermes cron jobs

This directory stores declarative definitions for Hermes cron jobs whose prompt/toolset settings should be reviewed and versioned in Git.

## Current jobs

- `ai-cost-news.json`: Daily AI cost news briefing for Telegram delivery.
  - Schedule: `0 22 * * *` in Hermes cron state, which corresponds to 07:00 KST the next day.
  - Date scope: this 07:00 KST cron briefing searches and filters only yesterday/today news.
  - Modal wake-up is handled separately by `modal_app.py::cron_tick` using `modal.Cron("0 7 * * *", timezone="Asia/Seoul")`.
- `english-random-expressions.json`: Daily random English expression briefing from `benelog/english` docs.
  - Schedule: `0 22 * * *` in Hermes cron state, which corresponds to 07:00 KST the next day.
  - Script: `english_random_expressions.py`, shipped from `scripts/cron/` into `~/.hermes/scripts/` by `scripts/prepare_runtime.py`.
  - The script keeps a local shallow clone of `https://github.com/benelog/english.git` and runs `git pull --ff-only origin main` on every execution before sampling expressions.
- `weekly-git-activity.json`: Weekly learning/activity briefing generated from recent Git commit logs across `/root/workspace` repositories.
  - Schedule: `0 22 * * 5` in Hermes cron state, which corresponds to Saturday 07:00 KST.
  - Script: `weekly_commit_activity.py`, shipped from `scripts/cron/` into `~/.hermes/scripts/` by `scripts/prepare_runtime.py`.
  - The script discovers local Git repositories, fetches remote refs, and summarizes commits from the last week with public benelog URLs when derivable.

## Adding new cron jobs

When a new Hermes cron job is created or materially changed in runtime, mirror it here in the same commit:

1. Add or update a sanitized `cron_jobs/*.json` definition with declarative fields only.
2. If the job uses a pre-run script, commit it under `scripts/cron/`; `prepare_runtime.py` syncs that directory into `~/.hermes/scripts/` on Modal startup/cron tick.
3. Do not commit runtime-only fields: Telegram chat IDs, `origin`, `next_run_at`, `last_run_at`, statuses, delivery errors, sessions, or output files.
4. Run the dry-run/apply checks below before committing.

## Apply definitions to Hermes runtime state

From the repository root:

```bash
python scripts/apply_cron_jobs.py --dry-run
python scripts/apply_cron_jobs.py
```

The apply script updates existing Hermes cron jobs by `job_id`. It deliberately does **not** commit runtime-only fields such as Telegram chat IDs, `origin`, `next_run_at`, `last_run_at`, status, delivery errors, or output files.

If a job does not exist yet, create it first in the target Hermes environment, then update the `job_id` in the tracked JSON definition if needed.
