# hermes-modal

Zero-cost personal bot stack on Modal: Hermes Telegram gateway + qmd knowledge search (`modal_app.py`, `scripts/`).

## Commands
- Server tests: `python3 -m unittest discover -s tests` (no pytest)
- Deploy: `./deploy.sh` (server tests → interactive confirm → `modal deploy modal_app.py`) — PRODUCTION bot, confirm with user first (hook enforces; the script's prompt also blocks non-interactive runs unless `-y`). `modal` CLI is on PATH (pipx); for `import modal` scripting use the venv python: `"$(pipx environment --value PIPX_LOCAL_VENVS)/modal/bin/python"`

## Rules
- `scripts/skills/` + `scripts/SOUL.md` are auto-synced FROM the Modal volume ("Sync skills from Modal volume" commits) — pull before editing, expect upstream churn.
- Secrets/tokens/URLs: Modal Secrets only, never git.
- Code comments and user-facing strings are Korean — keep that convention.

## Docs
- `docs/research.md` — Modal/Hermes runtime facts
- `README.md` — setup/ops runbook (Korean)
