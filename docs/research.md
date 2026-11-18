# Platform research findings

Durable facts about the Modal/Hermes runtime.

## Modal platform

- `cron_tick` (07:00/19:00 KST) only HTTP-pings the gateway (`/cron-wake`, 404 is fine) every 60s for 15 min; the gateway's in-process ticker (`cron.scheduler.tick()` every 60s) runs due jobs. It no longer runs `hermes cron tick` in its own container and does not mount hermes-home.
- Never let two containers write the hermes-home SQLite (`state.db`, WAL) at once: each commits its own copy and the file corrupts. Old `cron_tick` overlapping a live gateway session did exactly this (2026-10-29 07:00 KST; messages stopped persisting until repaired 2026-11-19, corrupt copy kept as `state.db.corrupt-20261119`). Keep `state.db` writers to the gateway (`max_containers=1`); stop/idle it before replacing the file via `modal volume put`.
- Volume writes from the gateway persist via Modal's background/exit commit, not an explicit `commit()`.
- `hermes -z PROMPT` one-shot prints only the final LLM text to stdout — a Modal endpoint can reuse the resident Hermes model as a subprocess summarizer, no extra API key.
