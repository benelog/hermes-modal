# hermes-modal

Zero-cost personal bot stack on Modal: Hermes Telegram gateway + qmd knowledge search (`modal_app.py`, `scripts/`), plus a KakaoTalk room collector/summarizer (Android accessibility app in `kakao-collector/`).

## Commands
- Server tests: `python3 -m unittest discover -s tests` (no pytest)
- Android: `cd kakao-collector && ./install.sh assembleDebug|testDebugUnitTest|installDebug` (script handles JDK17/SDK)
- Deploy: `modal deploy modal_app.py` — PRODUCTION bot, confirm with user first (hook enforces). `modal` only in pipx venv: `/home/benelog/.local/share/pipx/venvs/modal/bin/python`
- adb: `~/Android/Sdk/platform-tools/adb` (not on PATH)

## Rules
- After `installDebug`/force-stop of the collector app, the a11y service dies silently → run `kakao-collector/enable_service.sh`, verify `dumpsys accessibility | grep "label=Kakao Collector"` (hook checks after installs).
- `scripts/skills/` + `scripts/SOUL.md` are auto-synced FROM the Modal volume ("Sync skills from Modal volume" commits) — pull before editing, expect upstream churn.
- Secrets/tokens/URLs: Modal Secrets + app SharedPreferences only, never git.
- Code comments and UI strings are Korean — keep that convention.
- No sqlite3 CLI on host or device; use python3 `sqlite3`.

## Subagents (.claude/agents/)
- `device-doctor` — "collection stopped / 0 messages" device forensics (read-only)
- `android-build` — gradle build/test/install incl. post-install a11y re-enable
- `kakao-data-auditor` — 오수집 audit of phone DB + Modal Dict (plan-only)

## Docs
- `docs/decisions.md` — decisions in effect; read before changing collector/server design
- `docs/research.md` — platform facts (Kakao a11y tree, Android service kills, Modal, BAND)
- `README.md`, `kakao-collector/README.md` — setup/ops runbooks (Korean)
