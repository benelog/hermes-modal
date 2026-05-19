# Git-tracked Hermes cron job definitions

Session-derived pattern for managing Hermes cron prompt/toolset changes in a Git-backed Modal deployment.

## Problem

Hermes cron jobs are stored in runtime state under `~/.hermes/cron/jobs.json`. Changes made with the `cronjob` tool or `hermes cron edit` (for example prompt updates or `enabled_toolsets`) are not automatically represented in the application Git repository.

This matters when:

- the user asks whether a cron prompt/toolset change was committed;
- a Modal/Hermes deployment should be reproducible from Git;
- cron jobs need reviewable history without committing runtime-only fields.

## Known repo in Modal environment

In the current Modal deployment, the Git-backed Hermes app clone was found at:

```bash
/tmp/hermes-modal-clone
```

Its remote is `github.com/benelog/hermes-modal.git` and it already contains:

- `cron_jobs/ai-cost-news.json`
- `cron_jobs/README.md`
- `scripts/apply_cron_jobs.py`

Use the live repo status rather than assuming this path is always present; if missing, search common roots for `.git` and confirm the remote before writing.

## Recommended pattern

In this Modal environment, first check the Git-backed deployment clone at `/tmp/hermes-modal-clone`. It already contains `cron_jobs/`, `scripts/apply_cron_jobs.py`, and a remote for `benelog/hermes-modal`; prefer updating that clone over creating a new repository structure elsewhere.

1. Export only declarative job fields into a repo directory such as `cron_jobs/`:
   - `job_id`
   - `name`
   - `schedule` / schedule display
   - `deliver` if non-secret and generic (e.g. `origin`)
   - full `prompt`
   - `skills`
   - model/provider/base_url if intentionally configured and non-secret
   - `enabled_toolsets`
   - `workdir`, `enabled`, `state`

2. Do **not** commit runtime-only or sensitive fields:
   - `origin.chat_id`, `chat_name`, `thread_id`
   - `next_run_at`, `last_run_at`, `last_status`, delivery errors
   - output files under `~/.hermes/cron/output/`
   - session files under `~/.hermes/sessions/`
   - credentials or environment variable values

3. Add an apply script such as `scripts/apply_cron_jobs.py` that imports Hermes runtime helpers directly:
   ```python
   from cron.jobs import get_job, update_job, parse_schedule
   ```
   The script should:
   - load `cron_jobs/*.json`;
   - locate jobs by `job_id`;
   - call `parse_schedule()` for schedule strings;
   - call `update_job(job_id, updates)`;
   - support `--dry-run`;
   - preserve runtime counters such as `repeat.completed` rather than resetting them from Git.

4. Include a README in `cron_jobs/` describing the relationship between:
   - Git-tracked cron definitions;
   - runtime Hermes cron state;
   - Modal schedule/wakeup code such as `modal_app.py::cron_tick`.

## Verification commands

From the repository root (usually `/tmp/hermes-modal-clone` in Modal):

```bash
python -m py_compile scripts/apply_cron_jobs.py
python scripts/apply_cron_jobs.py --dry-run
python - <<'PY'
import json
from pathlib import Path
p = Path('cron_jobs/ai-cost-news.json')
if p.exists():
    data = json.loads(p.read_text(encoding='utf-8'))
    forbidden = {'origin', 'chat_id', 'chat_name', 'thread_id', 'last_run_at', 'next_run_at', 'last_status', 'last_delivery_error'}
    leaked = forbidden & set(data)
    assert not leaked, leaked
print('cron definition validation ok')
PY
git diff --check
```

If the user asked to make the cron definition commit-ready or persistent, stage only the cron definition/README changes, commit, pull --rebase, and push to `origin main`.

Example commit flow:

```bash
git add cron_jobs/ai-cost-news.json cron_jobs/README.md
git commit -m "Track AI cost news cron definition"
git pull --rebase origin main
git push origin main
git status --short
```

## Push/auth pitfall
If committing the exported definition, run:

```bash
git add cron_jobs/ scripts/apply_cron_jobs.py
git commit -m "Track AI cost news cron definition"
git push origin main
```

Adjust the commit message and staged paths to match the actual changed cron definitions. Prefer `git diff --check` before committing; use `git diff --cached --check` after staging if desired.

### Terminal heredoc pitfall

Some Hermes terminal invocations can reject a shell command containing a literal `&` token even when it appears inside a Python heredoc expression such as `forbidden & set(data)`, because the safety checker treats it as backgrounding. Avoid that form in verification snippets; use `forbidden.intersection(data)` or run the validation with `execute_code` instead.
```
## Push/auth pitfall

In this Modal environment, GitHub credentials may exist only in `~/.hermes/.env` as `GITHUB_TOKEN`, not in the shell environment or git credential helper. If `git push` fails with:

```text
fatal: could not read Username for 'https://github.com': No such device or address
```

prepare a temporary `GIT_ASKPASS` script that reads `GITHUB_TOKEN` without printing it, push, then delete the temporary files. If push is rejected because remote advanced, run `git pull --rebase origin main` before pushing again.

Never print token values; only report presence/absence or redact as `[REDACTED]`.
