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

## Pitfalls

Do not tell users that Modal automatically wakes for Hermes cron jobs unless the Modal deployment actually has a scheduled function/trigger configured. Distinguish clearly between:

- Hermes cron: stores jobs and executes due work when `tick()` runs.
- Hermes gateway: runs an in-process ticker every ~60s while alive.
- Modal: wakes containers only for configured triggers such as webhooks or Modal schedules.

When testing a Modal scheduled function with `modal run modal_app.py::cron_tick`, run it from the project directory that contains all local assets referenced by the image (for example `scripts/` if the image uses `.add_local_dir("scripts", ...)`). Running from a directory that only has `modal_app.py` can fail locally with `FileNotFoundError('local dir /root/scripts does not exist')` even if the deployed app itself has the scheduled function.

`hermes cron status` showing "Gateway is running — cron jobs will fire automatically" only proves the currently running gateway process will tick cron while alive. It does not prove Modal has a scheduled trigger for scale-to-zero wakeups; inspect `modal_app.py` and deployed functions for that.