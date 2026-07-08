# Manual test run for ABC weekly summary

Use this when the user asks to run the Monday 07:00 ABC weekly summary immediately for review/feedback.

## Key lesson

Triggering the job with `cronjob(action="run")` or `hermes cron run <job_id>` can only move the job to the next scheduler tick. It may not produce output until a cron tick actually runs. Do not tell the user to simply wait unless you have confirmed the scheduler/tick will run.

## Reliable manual execution pattern

1. Trigger the job:
   ```bash
   hermes cron run 925bfae8de0d
   ```
2. Immediately process due jobs:
   ```bash
   hermes cron tick
   ```
3. Verify completion:
   ```bash
   hermes cron list
   find /root/.hermes/cron/output/925bfae8de0d -maxdepth 1 -type f -printf '%TY-%Tm-%Td %TH:%TM:%TS %p\n' | sort | tail
   ```
4. If the user did not receive the Telegram message, inspect logs and the saved output file:
   ```bash
   tail -n 120 /root/.hermes/logs/agent.log | grep -E '925bfae8de0d|abc-weekly|cron.scheduler|delivered'
   ```

## Reporting to the user

- Report whether the job actually ran (`last_status`, `last_run_at`) rather than only saying it was triggered.
- If delivery is logged as successful but the user does not see it, offer to paste the saved output from `/root/.hermes/cron/output/925bfae8de0d/*.md`.
- For review runs, preserve the normal weekly format; do not add extra debugging commentary to the summary itself.
