#!/usr/bin/env python3
"""Create/update the Modal Secret used by modal_app.py from local Hermes config.

This script does not print secret values. It copies safe runtime values from
~/.hermes/.env and encodes ~/.hermes/auth.json as HERMES_AUTH_JSON_B64 so the
cloud Hermes instance can use the same OAuth credential pool if you keep the
current openai-codex provider.

GITHUB_TOKEN is intentionally NOT included here. `modal secret create --force`
replaces the whole secret, so any UI-managed GITHUB_TOKEN would be wiped.
Manage GITHUB_TOKEN in the separate `github-secret` Modal Secret instead;
modal_app.py mounts both secrets together.
"""

from __future__ import annotations

import base64
import os
import secrets
import subprocess
import tempfile
from pathlib import Path

SECRET_NAME = "hermes-modal-secrets"
LOCAL_ENV = Path.home() / ".hermes" / ".env"
LOCAL_AUTH = Path.home() / ".hermes" / "auth.json"

COPY_ENV_KEYS = [
    "TELEGRAM_BOT_TOKEN",
    "TELEGRAM_ALLOWED_USERS",
    "HERMES_GATEWAY_TOKEN",
    "HERMES_MAX_ITERATIONS",
    "OPENROUTER_API_KEY",
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "GOOGLE_API_KEY",
    "GEMINI_API_KEY",
    "GIT_SSH_PRIVATE_KEY",
    "QMD_EMBED_MODEL",
    "QMD_GENERATE_MODEL",
    "QMD_RERANK_MODEL",
]
# GITHUB_TOKEN is deliberately omitted — see module docstring.

DEFAULTS = {
    "HERMES_MODEL_PROVIDER": "openai-codex",
    "HERMES_MODEL": "gpt-5.5",
    "HERMES_MODEL_BASE_URL": "https://chatgpt.com/backend-api/codex",
    "HERMES_PERSONALITY": "kawaii",
    "TELEGRAM_WEBHOOK_PORT": "8443",
    "QMD_LLAMA_GPU": "false",
}


def parse_env(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        values[key] = value
    return values


def build_secret_values(webhook_url: str | None) -> dict[str, str]:
    local = parse_env(LOCAL_ENV)
    values = dict(DEFAULTS)
    for key in COPY_ENV_KEYS:
        value = os.environ.get(key) or local.get(key)
        if value:
            values[key] = value

    if LOCAL_AUTH.exists() and "HERMES_AUTH_JSON_B64" not in values:
        values["HERMES_AUTH_JSON_B64"] = base64.b64encode(LOCAL_AUTH.read_bytes()).decode("ascii")

    values["TELEGRAM_WEBHOOK_SECRET"] = (
        os.environ.get("TELEGRAM_WEBHOOK_SECRET")
        or local.get("TELEGRAM_WEBHOOK_SECRET")
        or secrets.token_urlsafe(32)
    )
    if webhook_url:
        values["TELEGRAM_WEBHOOK_URL"] = webhook_url.rstrip("/")
    elif local.get("TELEGRAM_WEBHOOK_URL"):
        values["TELEGRAM_WEBHOOK_URL"] = local["TELEGRAM_WEBHOOK_URL"].rstrip("/")

    return values


def dotenv_quote(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')
    return f'"{escaped}"'


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--webhook-url", help="Full Telegram webhook URL, usually https://...modal.run/telegram")
    parser.add_argument("--name", default=SECRET_NAME)
    args = parser.parse_args()

    values = build_secret_values(args.webhook_url)
    if "TELEGRAM_BOT_TOKEN" not in values:
        raise SystemExit("TELEGRAM_BOT_TOKEN not found in ~/.hermes/.env or environment")

    if os.environ.get("GITHUB_TOKEN") or parse_env(LOCAL_ENV).get("GITHUB_TOKEN"):
        print(
            "Note: GITHUB_TOKEN found in environment but skipped. "
            "Put it in the separate `github-secret` Modal Secret instead."
        )

    print(f"Creating/updating Modal secret {args.name} with keys:")
    for key in sorted(values):
        print(f"  - {key}")

    with tempfile.NamedTemporaryFile("w", encoding="utf-8", prefix="hermes-modal-secrets-", suffix=".env", delete=True) as fp:
        for key, value in values.items():
            fp.write(f"{key}={dotenv_quote(value)}\n")
        fp.flush()
        cmd = ["modal", "secret", "create", "--force", args.name, "--from-dotenv", fp.name]
        subprocess.run(cmd, check=True)


if __name__ == "__main__":
    main()
