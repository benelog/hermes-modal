# Cron prompt deduplication for abc-weekly-summary

## Lesson

For the Monday 07:00 KST `abc-weekly-summary` cron job, keep the detailed workflow in `SKILL.md` and keep the Git-tracked cron prompt thin. The cron prompt should not restate the skill's full Procedure, Output rules, Suggested output shape, book/URL enrichment order, or Band formatting rules.

## Recommended shape

The cron prompt should only provide runtime context that is specific to the scheduled job:

- request a weekly summary for `ABC(아카라카북클럽)`;
- say that `abc-weekly-summary` is loaded and its Scope, Procedure, Output rules, and Suggested output shape are authoritative;
- state that this cron runs Monday 07:00 Asia/Seoul and covers the previous Monday 00:00 through previous Sunday 23:59:59;
- say to prefer the pre-run script `abc_book_enrichment.py` stdout JSON, and re-run/verify with the skill procedure only if the stdout is empty or suspicious;
- say the final response should contain only the user-facing Band copy/paste Korean plain text summary.

## Why

This avoids drift between the skill and `cron_jobs/abc-weekly-summary.json`. Future output changes should usually be made in `SKILL.md`; the cron JSON should remain a lightweight invocation wrapper.

## Verification after editing Git-tracked cron JSON

From the `benelog/hermes-modal` repo root:

```bash
python -m json.tool cron_jobs/abc-weekly-summary.json >/dev/null
python -m py_compile scripts/apply_cron_jobs.py
git diff --check
python scripts/apply_cron_jobs.py --dry-run
```

If the user expects persistence, commit and push the cron JSON change. If the live runtime already matches the Git prompt, `apply_cron_jobs.py --dry-run` may report the job as already up to date.
