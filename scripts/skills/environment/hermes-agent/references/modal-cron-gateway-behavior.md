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

```bash
hermes cron status
hermes cron list
```

Useful source checks in a live install:

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

## Pitfall

Do not tell users that Modal automatically wakes for Hermes cron jobs unless the Modal deployment actually has a scheduled function/trigger configured. Distinguish clearly between:

- Hermes cron: stores jobs and executes due work when `tick()` runs.
- Hermes gateway: runs an in-process ticker every ~60s while alive.
- Modal: wakes containers only for configured triggers such as webhooks or Modal schedules.
