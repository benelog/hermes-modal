#!/usr/bin/env python3
"""End-to-end credential refresh for the hermes-modal Telegram bot.

Run this when the Modal gateway is failing with
'Provider authentication failed: Codex refresh token was already consumed
by another client'. Steps:

  1. Re-authenticate Codex CLI in the browser (`codex-relogin`).
  2. Mirror the fresh tokens from ~/.codex/auth.json into ~/.hermes/auth.json
     (top-level providers entry + every credential_pool entry).
  3. Upload the refreshed Hermes auth.json to Modal Secret hermes-modal-secrets.
  4. modal deploy modal_app.py.
  5. modal run modal_app.py::bootstrap_auth — force the Modal volume's
     auth.json to be reseeded with the new credentials (prepare_runtime.py
     would otherwise leave the stale volume copy in place).

Pass --skip-codex if ~/.codex/auth.json is already fresh and you just want
to propagate it to Modal.
"""

from __future__ import annotations

import argparse
import datetime
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CODEX_AUTH = Path.home() / ".codex" / "auth.json"
HERMES_AUTH = Path.home() / ".hermes" / "auth.json"

CODEX_RELOGIN_CMD = "codex-relogin"
CREATE_SECRET_SCRIPT = ROOT / "scripts" / "create_modal_secret.py"
MODAL_APP = ROOT / "modal_app.py"


def banner(step: int, total: int, msg: str) -> None:
    print(f"\n==> [{step}/{total}] {msg}", flush=True)


def run_codex_relogin() -> None:
    if shutil.which(CODEX_RELOGIN_CMD) is None:
        sys.exit(
            f"'{CODEX_RELOGIN_CMD}' not found in PATH. "
            "Install it (see ~/.local/bin/codex-relogin) or run with --skip-codex."
        )
    rc = subprocess.run([CODEX_RELOGIN_CMD], check=False).returncode
    if rc != 0:
        sys.exit(f"codex-relogin failed (exit {rc})")


def patch_hermes_auth() -> None:
    if not CODEX_AUTH.exists():
        sys.exit(f"{CODEX_AUTH} not found — run codex login first")
    if not HERMES_AUTH.exists():
        sys.exit(f"{HERMES_AUTH} not found — run `hermes auth add openai-codex` first")

    codex = json.loads(CODEX_AUTH.read_text())
    hermes = json.loads(HERMES_AUTH.read_text())

    fresh_access = codex["tokens"]["access_token"]
    fresh_refresh = codex["tokens"]["refresh_token"]
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()

    prov = hermes["providers"]["openai-codex"]
    prov["tokens"]["access_token"] = fresh_access
    prov["tokens"]["refresh_token"] = fresh_refresh
    prov["last_refresh"] = now

    pool = hermes["credential_pool"]["openai-codex"]
    for entry in pool:
        entry["access_token"] = fresh_access
        entry["refresh_token"] = fresh_refresh
        entry["last_refresh"] = now
        for k in (
            "last_status",
            "last_status_at",
            "last_error_code",
            "last_error_reason",
            "last_error_message",
            "last_error_reset_at",
        ):
            entry[k] = None

    hermes["updated_at"] = now

    backup = HERMES_AUTH.with_suffix(
        f".json.bak.{datetime.datetime.now().strftime('%Y%m%d-%H%M%S')}"
    )
    backup.write_bytes(HERMES_AUTH.read_bytes())
    HERMES_AUTH.write_text(json.dumps(hermes, indent=2) + "\n")
    print(f"  patched {HERMES_AUTH} ({len(pool)} pool entries)")
    print(f"  backup  {backup}")


def upload_modal_secret() -> None:
    subprocess.run([sys.executable, str(CREATE_SECRET_SCRIPT)], check=True)


def deploy_modal() -> None:
    subprocess.run(["modal", "deploy", str(MODAL_APP)], check=True)


def bootstrap_auth_on_volume() -> None:
    subprocess.run(["modal", "run", f"{MODAL_APP}::bootstrap_auth"], check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--skip-codex",
        action="store_true",
        help="Skip codex-relogin; assume ~/.codex/auth.json is already fresh.",
    )
    parser.add_argument(
        "--skip-deploy",
        action="store_true",
        help="Skip `modal deploy` (use if the deployed app already includes bootstrap_auth).",
    )
    args = parser.parse_args()

    total = 5 - int(args.skip_codex) - int(args.skip_deploy)
    step = 1

    if not args.skip_codex:
        banner(step, total, "Codex CLI re-authentication (browser OAuth)")
        run_codex_relogin()
        step += 1

    banner(step, total, "Patch ~/.hermes/auth.json with fresh Codex tokens")
    patch_hermes_auth()
    step += 1

    banner(step, total, "Upload refreshed Hermes auth to Modal Secret")
    upload_modal_secret()
    step += 1

    if not args.skip_deploy:
        banner(step, total, "modal deploy modal_app.py")
        deploy_modal()
        step += 1

    banner(step, total, "Force-reseed Modal volume's auth.json (bootstrap_auth)")
    bootstrap_auth_on_volume()

    print("\nDone. Send a Telegram message to verify, or watch logs:")
    print("  modal app logs hermes-telegram-gateway --timestamps")
    return 0


if __name__ == "__main__":
    sys.exit(main())
