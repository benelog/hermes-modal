# Modal + Hermes cron/gateway behavior

Session-derived notes for explaining or troubleshooting scheduled Hermes jobs in a Modal deployment.

## Key finding

Hermes cron jobs do not, by themselves, wake a stopped Modal container. In the installed Hermes code, cron execution is piggy-backed on the gateway process:

- `hermes cron status` reports automatic firing only when the gateway process is running.
- `hermes_cli/cron.py` warns that if `find_gateway_pids()` returns no PID, jobs will not fire automatically.
- `cron.scheduler` exposes `tick()`; the gateway calls it periodically.
- `gateway/run.py::_start_cron_ticker(...)` starts a background thread and calls `cron.scheduler.tick(...)` every 60 seconds while the gateway process is alive.

## Practical implications

For a Modal Telegram bot deployed as webhook-only/serverless:

- Telegram webhook traffic can wake the Modal app/container to process incoming messages.
- If the container is idle/stopped at the scheduled time, Hermes' in-process cron ticker is not running.
- A Hermes cron job registered with `cronjob` or `hermes cron create` may not execute reliably unless something keeps or wakes the gateway process.

## Reliable deployment patterns

1. Keep the gateway process alive / warm enough that `_start_cron_ticker` runs.
   - Simple for Hermes cron, but may increase Modal runtime cost.

2. Add a Modal scheduled trigger that wakes the app and invokes:
   ```bash
   hermes cron tick
   ```
   - More serverless-friendly.
   - The tick command checks due jobs once and exits.
   - Can be scheduled at the exact desired time or every minute/few minutes depending on tolerance.

## Verification commands

Local Hermes state:

```bash
hermes cron status
hermes cron list
```

When the user asks why a scheduled job "didn't run" shortly after its scheduled time, first check whether it is still in progress before concluding failure. A Hermes cron job may start on time but take minutes to search/summarize before delivery. Inspect the freshest session/output files, then re-check `hermes cron list` after a short delay:

```bash
python - <<'PY'
from pathlib import Path
from datetime import datetime, timezone
job = '26a77a1a8a50'  # replace with the job id from hermes cron list
for base in [Path('~/.hermes/sessions').expanduser(), Path(f'~/.hermes/cron/output/{job}').expanduser()]:
    print('BASE', base)
    if not base.exists():
        continue
    files = [p for p in base.glob('*') if p.is_file() and (job in p.name or base.name == job)]
    for p in sorted(files, key=lambda p: p.stat().st_mtime, reverse=True)[:5]:
        print(p, p.stat().st_size, datetime.fromtimestamp(p.stat().st_mtime, timezone.utc).isoformat())
PY
hermes cron list
```

If a `session_cron_<job>_<timestamp>.json` exists but no output file or `last_run_at` update yet, the job may be actively running. Re-read the session JSON for message count/final assistant content, and only report failure if `last_status`, logs, or session content show an error.

Check the Modal app source in the live working directory (in this environment it has been `/root/modal_app.py`):

```bash
grep -n "schedule=modal.Cron\|def cron_tick\|hermes.*cron.*tick" modal_app.py
```

A known-good pattern observed in this deployment:

```python
@app.function(
    ...,
    schedule=modal.Cron("0 7 * * *", timezone="Asia/Seoul"),
    ...,
)
def cron_tick():
    ...
    subprocess.run(["hermes", "cron", "tick"], ...)
```

Verify the deployed Modal app includes the scheduled function, not just local source:

```bash
modal app list --json
modal app history hermes-telegram-gateway
python - <<'PY'
import asyncio
from modal.client import _Client
from modal_proto import api_pb2

async def main():
    client = await _Client.from_env()
    resp = await client.stub.AppGetByDeploymentName(
        api_pb2.AppGetByDeploymentNameRequest(
            name="hermes-telegram-gateway",
            environment_name="main",
        )
    )
    print("app_id", resp.app_id)
    layout = await client.stub.AppGetLayout(api_pb2.AppGetLayoutRequest(app_id=resp.app_id))
    for obj in layout.app_layout.objects:
        md = obj.function_handle_metadata
        if md.function_name:
            print(obj.object_id, md.function_name, "web_url=", md.web_url, "type=", md.function_type)
PY
```

Useful source checks in a live Hermes install:

```bash
python - <<'PY'
import inspect, hermes_cli.cron
print(inspect.getfile(hermes_cli.cron))
PY

python - <<'PY'
import os
base='/usr/local/lib/python3.11/site-packages'
for root, dirs, files in os.walk(base):
    for fn in files:
        if fn.endswith('.py'):
            p=os.path.join(root, fn)
            try:
                s=open(p, errors='ignore').read()
            except Exception:
                continue
            if 'cron.scheduler' in s or '_start_cron_ticker' in s:
                print(p)
PY
```

## Manual test of a scheduled Hermes job

To test a daily job immediately without changing its normal schedule:

```bash
hermes cron run <job_id>   # or use the cronjob tool action=run
hermes cron tick
hermes cron list
```

Then inspect the latest generated artifacts:

```bash
python - <<'PY'
from pathlib import Path
job = '<job_id>'
for base in [Path(f'~/.hermes/cron/output/{job}').expanduser(), Path('~/.hermes/sessions').expanduser()]:
    print('BASE', base)
    files = sorted(base.glob('*'), key=lambda p: p.stat().st_mtime, reverse=True)[:5]
    for p in files:
        if base.name == 'sessions' and job not in p.name:
            continue
        print(p, p.stat().st_size)
PY
```

For web-tool troubleshooting, read the newest session JSON and confirm which tools were actually present. If `web_search` is absent but `terminal`/`execute_code` are present, a news job can still succeed via public RSS fallback; the output should say that it used RSS rather than the dedicated web backend.

## Pitfalls

Do not tell users that Modal automatically wakes for Hermes cron jobs unless the Modal deployment actually has a scheduled function/trigger configured. Distinguish clearly between:

- Hermes cron: stores jobs and executes due work when `tick()` runs.
- Hermes gateway: runs an in-process ticker every ~60s while alive.
- Modal: wakes containers only for configured triggers such as webhooks or Modal schedules.

When testing a Modal scheduled function with `modal run modal_app.py::cron_tick`, run it from the project directory that contains all local assets referenced by the image (for example `scripts/` if the image uses `.add_local_dir("scripts", ...)`). Running from a directory that only has `modal_app.py` can fail locally with `FileNotFoundError('local dir /root/scripts does not exist')` even if the deployed app itself has the scheduled function.

`hermes cron status` showing "Gateway is running — cron jobs will fire automatically" only proves the currently running gateway process will tick cron while alive. It does not prove Modal has a scheduled trigger for scale-to-zero wakeups; inspect `modal_app.py` and deployed functions for that.

A toolset can be enabled for cron but still load no tools at runtime if the toolset's availability check fails. For web search specifically, `hermes tools list --platform cron` may show `web` enabled, while cron session JSON still has `"tools": []` because no web backend is configured. Diagnose with:

```bash
python /usr/local/lib/python3.11/site-packages/tools/web_tools.py
python - <<'PY'
from model_tools import get_tool_definitions
for toolsets in [['web'], ['web', 'terminal', 'code_execution']]:
    tools = get_tool_definitions(
        enabled_toolsets=toolsets,
        disabled_toolsets=['cronjob', 'messaging', 'clarify'],
        quiet_mode=True,
    )
    print(toolsets, [t['function']['name'] for t in tools])
PY
```

If the diagnostic says `No web search backend configured`, add one of `EXA_API_KEY`, `PARALLEL_API_KEY`, `TAVILY_API_KEY`, `FIRECRAWL_API_KEY`, `FIRECRAWL_API_URL`, or Nous Tool Gateway settings to the Modal secret / Hermes environment and optionally set `web.backend`. As a fallback for scheduled news jobs, include `terminal` and `code_execution` in `enabled_toolsets` and explicitly instruct the job to query public RSS/news endpoints if `web_search` is unavailable.

## Git-tracking Hermes cron job definitions

Hermes cron job state normally lives in `~/.hermes/cron/jobs.json`, not in the Modal app git repository. When the user wants cron prompt/toolset changes to be managed in git, store only declarative, non-secret, non-runtime fields in a repo file such as `cron_jobs/<job-name>.json`:

- include: `job_id`, `name`, `schedule`, `deliver`, `prompt`, `skills`, `model`, `provider`, `base_url`, `script`, `context_from`, `enabled_toolsets`, `workdir`, `enabled`, `state`.
- exclude: `origin` details such as Telegram `chat_id`/`chat_name`, `next_run_at`, `last_run_at`, `last_status`, delivery errors, output paths, run counters, and any tokens/secrets.

A useful repo pattern is an apply script, e.g. `scripts/apply_cron_jobs.py`, that imports `cron.jobs.get_job`, `cron.jobs.update_job`, and `cron.jobs.parse_schedule`, then updates existing jobs by `job_id`. Verify with:

```bash
python -m py_compile scripts/apply_cron_jobs.py
python scripts/apply_cron_jobs.py --dry-run
```

The dry run should report `already up to date` after applying. Commit and push the tracked definition and apply script when the user's repo convention is to keep these changes in source control.

## Manual test of a scheduled Hermes job

To test a 7am cron job immediately without changing the long-term schedule:

```bash
hermes cron run <job_id>
hermes cron tick
hermes cron list
```

Then inspect the newest output and session files under:

```text
~/.hermes/cron/output/<job_id>/
~/.hermes/sessions/session_cron_<job_id>_*.json
```

For a web-search fallback test, confirm `last_status: ok`, `last_delivery_error: null`, and check the session `tools` list. If `web_search` is still absent but `terminal`/`execute_code` are present and the output cites Google News RSS or other public feeds, the fallback is working even though the real web backend is still unconfigured.

For the user's AI-cost news briefing, prefer Korean-language articles first. If Korean results are insufficient or a major global original is more important, supplement with English/global sources while stating that Korean articles were prioritized.