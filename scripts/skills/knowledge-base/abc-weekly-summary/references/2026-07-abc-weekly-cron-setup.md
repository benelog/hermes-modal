# 2026-07 ABC weekly summary cron setup notes

Session-derived notes from creating the first weekly ABC summary job.

## Confirmed implementation pattern

- Skill name: `abc-weekly-summary`.
- Live cron job name: `abc-weekly-summary`.
- Live cron job id from the initial setup: `925bfae8de0d`.
- Target user-facing time: every Monday 07:00 in Asia/Seoul.
- Hermes cron expression used successfully: `0 22 * * 0`.
  - This is Sunday 22:00 UTC, corresponding to Monday 07:00 KST.
  - Numeric weekday worked; the symbolic form `MON` was rejected by the cron parser in this session.

## Prompt requirements that mattered

The recurring prompt should be self-contained because cron jobs run in a fresh session. Include all of these in the cron prompt, not only in the skill:

- Load/follow `abc-weekly-summary`.
- Confirm current Korea time with `TZ=Asia/Seoul date`.
- Compute previous Monday through previous Sunday inclusively.
- Fetch with `/root/.hermes/scripts/kakao_fetch.py --since 9day`.
- Filter locally by `room == "ABC(아카라카북클럽)"` and `client_time[:10]` in range.
- Put books first, URLs second, then key topics / decisions / one-line mood.
- Include authors where known; write `저자 미확인` rather than guessing.
- For book-like URL or image shares without captured title text, infer only from URL title and surrounding messages; if image bytes are unavailable, say so plainly.
- Keep Band copy/paste output mostly plain text and do not use emoji in the body.

## Git tracking pattern used

For this user's Modal deployment, cron additions/material changes should be mirrored into the `benelog/hermes-modal` repo as sanitized JSON under `cron_jobs/` and committed/pushed by default.

For the initial job, the tracked file was:

```text
cron_jobs/abc-weekly-summary.json
```

Sanitized fields included:

- `job_id`, `name`, `schedule`, `repeat`, `deliver`
- full `prompt`
- `skills`
- `model`, `provider`, `base_url`
- `script`, `context_from`, `enabled_toolsets`, `workdir`
- `enabled`, `state`, `notes`

Runtime-only or sensitive fields were intentionally excluded: origin chat ids, thread ids, next/last run timestamps, last status, delivery errors, and output paths.

## Verification used

After writing the tracked JSON, run the repo's dry-run apply script and JSON validation before commit:

```bash
python -m json.tool cron_jobs/abc-weekly-summary.json >/tmp/abc-weekly-summary.pretty.json
python scripts/apply_cron_jobs.py --dry-run
python - <<'PY'
import json
from pathlib import Path
p = Path('cron_jobs/abc-weekly-summary.json')
data = json.loads(p.read_text(encoding='utf-8'))
forbidden = {'origin', 'chat_id', 'chat_name', 'thread_id', 'last_run_at', 'next_run_at', 'last_status', 'last_delivery_error'}
leaked = forbidden.intersection(data)
assert not leaked, leaked
print('cron definition validation ok')
PY
git diff --check
git status --short
```

Then commit/push the cron definition file.

## Pitfall

Do not write the weekly schedule as `0 7 * * 1` unless the runtime has explicit timezone handling for cron expressions. In this session, `0 7 * * 1` scheduled 07:00 UTC, not 07:00 KST, so it was corrected to `0 22 * * 0`.
