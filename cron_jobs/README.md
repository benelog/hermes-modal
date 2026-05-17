# Git-tracked Hermes cron jobs

This directory stores declarative definitions for Hermes cron jobs whose prompt/toolset settings should be reviewed and versioned in Git.

## Current jobs

- `ai-cost-news.json`: Daily AI cost news briefing for Telegram delivery.
  - Schedule: `0 22 * * *` in Hermes cron state, which corresponds to 07:00 KST the next day.
  - Modal wake-up is handled separately by `modal_app.py::cron_tick` using `modal.Cron("0 7 * * *", timezone="Asia/Seoul")`.

## Apply definitions to Hermes runtime state

From the repository root:

```bash
python scripts/apply_cron_jobs.py --dry-run
python scripts/apply_cron_jobs.py
```

The apply script updates existing Hermes cron jobs by `job_id`. It deliberately does **not** commit runtime-only fields such as Telegram chat IDs, `origin`, `next_run_at`, `last_run_at`, status, delivery errors, or output files.

If a job does not exist yet, create it first in the target Hermes environment, then update the `job_id` in the tracked JSON definition if needed.
