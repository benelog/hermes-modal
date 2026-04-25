#!/usr/bin/env python3
"""Prepare Hermes + qmd runtime inside Modal volumes.

This script is intentionally idempotent. It can run on every container start
(`--fast`) and as an explicit qmd synchronization job (`--sync-qmd`).
"""

from __future__ import annotations

import argparse
import base64
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

import yaml

HERMES_HOME = Path(os.environ.get("HERMES_HOME", "/root/.hermes"))
WORKSPACE = Path(os.environ.get("HERMES_WORKSPACE", "/root/workspace"))
QMD_CONFIG_DIR = Path(os.environ.get("QMD_CONFIG_DIR", "/root/.config/qmd"))
QMD_INDEX_NAME = os.environ.get("QMD_INDEX_NAME", "index")

BASE = WORKSPACE / "benelog"
DEFAULT_REPO_MANIFEST = Path(__file__).with_name("qmd_repos.yml")
SOUL_SOURCE = Path(__file__).with_name("SOUL.md")


def load_repo_specs(path: Path = DEFAULT_REPO_MANIFEST) -> list[dict[str, str]]:
    """Load qmd collection repo specs captured from local git-backed dirs."""
    data: dict[str, Any] = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    specs: list[dict[str, str]] = []
    for raw in data.get("repos", []):
        spec = {
            "name": str(raw["name"]),
            "repo_url": str(raw["repo_url"]),
            "branch": str(raw.get("branch") or "master"),
            "collection_path": str(raw.get("collection_path") or "."),
            "pattern": str(raw.get("pattern") or "**/*.{md,adoc}"),
        }
        specs.append(spec)
    if not specs:
        raise RuntimeError(f"No qmd repo specs found in {path}")
    return specs


def sanitize_command(cmd: list[str] | str) -> str:
    printable = cmd if isinstance(cmd, str) else " ".join(cmd)
    return re.sub(r"https://x-access-token:[^@\s]+@github\.com/", "https://x-access-token:***@github.com/", printable)


def run(cmd: list[str] | str, *, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess:
    print(f"$ {sanitize_command(cmd)}")
    return subprocess.run(
        cmd,
        cwd=str(cwd) if cwd else None,
        check=check,
        text=True,
        shell=isinstance(cmd, str),
    )


def ensure_dirs() -> None:
    for path in [HERMES_HOME, HERMES_HOME / "logs", WORKSPACE, BASE, QMD_CONFIG_DIR]:
        path.mkdir(parents=True, exist_ok=True)


def maybe_write_auth_json() -> None:
    encoded = os.environ.get("HERMES_AUTH_JSON_B64", "").strip()
    if not encoded:
        return
    target = HERMES_HOME / "auth.json"
    decoded = base64.b64decode(encoded)
    if target.exists() and target.read_bytes() == decoded:
        return
    target.write_bytes(decoded)
    target.chmod(0o600)
    print(f"wrote {target}")


def write_soul_file() -> None:
    if not SOUL_SOURCE.exists():
        return
    target = HERMES_HOME / "SOUL.md"
    content = SOUL_SOURCE.read_text(encoding="utf-8")
    if target.exists() and target.read_text(encoding="utf-8", errors="ignore") == content:
        return
    target.write_text(content, encoding="utf-8")
    print(f"wrote {target}")


def write_hermes_config() -> None:
    config_path = HERMES_HOME / "config.yaml"
    if config_path.exists() and os.environ.get("HERMES_MODAL_OVERWRITE_CONFIG", "").lower() not in {"1", "true", "yes"}:
        return

    model_provider = os.environ.get("HERMES_MODEL_PROVIDER", "openai-codex")
    model_default = os.environ.get("HERMES_MODEL", "gpt-5.5")
    model_base_url = os.environ.get("HERMES_MODEL_BASE_URL", "https://chatgpt.com/backend-api/codex")

    config = {
        "model": {
            "default": model_default,
            "provider": model_provider,
            "base_url": model_base_url,
        },
        "agent": {
            "max_turns": int(os.environ.get("HERMES_MAX_ITERATIONS", "90")),
            "gateway_timeout": int(os.environ.get("HERMES_GATEWAY_TIMEOUT", "1800")),
            "reasoning_effort": os.environ.get("HERMES_REASONING_EFFORT", "medium"),
        },
        "display": {
            "personality": os.environ.get("HERMES_PERSONALITY", "kawaii"),
            "streaming": False,
            "final_response_markdown": "strip",
        },
        "memory": {
            "memory_enabled": True,
            "user_profile_enabled": True,
        },
        "stt": {
            # Local faster-whisper is intentionally disabled in Modal to avoid
            # heavy cold-starts. Configure Groq/OpenAI/Mistral later if needed.
            "enabled": False,
            "provider": "local",
        },
        "telegram": {
            "require_mention": True,
            "group_allowed_chats": ["-5132861071"],
            "channel_prompts": {},
        },
        "platform_toolsets": {
            "telegram": ["hermes-telegram"],
            "cli": ["hermes-cli"],
        },
        "mcp_servers": {
            "qmd": {
                "command": "qmd",
                "args": ["mcp"],
            }
        },
        "toolsets": ["hermes-cli"],
        "security": {
            "tirith_enabled": False,
            "redact_secrets": True,
        },
        "_config_version": 22,
    }

    config_path.write_text(yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8")
    config_path.chmod(0o600)
    print(f"wrote {config_path}")


def write_env_file() -> None:
    env_path = HERMES_HOME / ".env"
    keys = [
        "TELEGRAM_BOT_TOKEN",
        "TELEGRAM_ALLOWED_USERS",
        "TELEGRAM_WEBHOOK_URL",
        "TELEGRAM_WEBHOOK_SECRET",
        "TELEGRAM_WEBHOOK_PORT",
        "HERMES_GATEWAY_TOKEN",
        "OPENROUTER_API_KEY",
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "GOOGLE_API_KEY",
        "GEMINI_API_KEY",
        "GITHUB_TOKEN",
        "QMD_EMBED_MODEL",
        "QMD_GENERATE_MODEL",
        "QMD_RERANK_MODEL",
        "QMD_LLAMA_GPU",
    ]
    lines = []
    for key in keys:
        value = os.environ.get(key)
        if value:
            escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace('"', '\\"')
            lines.append(f'{key}="{escaped}"')
    env_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    env_path.chmod(0o600)
    print(f"wrote {env_path}")


def setup_git_ssh() -> None:
    key = os.environ.get("GIT_SSH_PRIVATE_KEY", "").strip()
    if not key:
        return
    ssh_dir = Path.home() / ".ssh"
    ssh_dir.mkdir(mode=0o700, exist_ok=True)
    key_path = ssh_dir / "id_ed25519"
    key_path.write_text(key.replace("\\n", "\n") + "\n", encoding="utf-8")
    key_path.chmod(0o600)
    known_hosts = ssh_dir / "known_hosts"
    if not known_hosts.exists():
        run("ssh-keyscan github.com >> ~/.ssh/known_hosts")
    os.environ["GIT_SSH_COMMAND"] = f"ssh -i {key_path} -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes"


def github_repo_slug(url: str) -> str | None:
    if url.startswith("git@github.com:") and url.endswith(".git"):
        return url.removeprefix("git@github.com:").removesuffix(".git")
    match = re.match(r"https://(?:[^/@]+@)?github\.com/(.+?)\.git$", url)
    if match:
        return match.group(1)
    return None


def repo_url(manifest_url: str) -> str:
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    slug = github_repo_slug(manifest_url)
    if token and slug:
        return f"https://x-access-token:{token}@github.com/{slug}.git"
    if slug:
        return f"https://github.com/{slug}.git"
    return manifest_url


def clone_or_pull_repos() -> None:
    setup_git_ssh()
    for spec in load_repo_specs():
        dest = BASE / spec["name"]
        if dest.exists() and (dest / ".git").exists():
            run(["git", "fetch", "--prune", "origin"], cwd=dest)
            run(["git", "checkout", spec["branch"]], cwd=dest)
            run(["git", "pull", "--ff-only", "origin", spec["branch"]], cwd=dest)
        else:
            if dest.exists():
                shutil.rmtree(dest)
            run(["git", "clone", "--branch", spec["branch"], "--depth", "1", repo_url(spec["repo_url"]), str(dest)])


def write_qmd_config() -> None:
    cfg = {"collections": {}}
    for spec in load_repo_specs():
        cfg["collections"][spec["name"]] = {
            "path": str(BASE / spec["name"] / spec["collection_path"]),
            "pattern": spec["pattern"],
        }

    target = QMD_CONFIG_DIR / f"{QMD_INDEX_NAME}.yml"
    target.write_text(yaml.safe_dump(cfg, sort_keys=False, allow_unicode=True), encoding="utf-8")
    print(f"wrote {target}")


def sync_qmd(embed: bool, force_embed: bool) -> None:
    clone_or_pull_repos()
    write_qmd_config()
    run(["qmd", "--index", QMD_INDEX_NAME, "update"])
    if embed or force_embed:
        cmd = ["qmd", "--index", QMD_INDEX_NAME, "embed"]
        if force_embed:
            cmd.append("-f")
        run(cmd)
    run(["qmd", "--index", QMD_INDEX_NAME, "status"])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fast", action="store_true", help="Only write config/env/auth; no git/qmd sync")
    parser.add_argument("--sync-qmd", action="store_true", help="Clone/pull repos and update qmd")
    parser.add_argument("--embed", action="store_true", help="Run qmd embed after update")
    parser.add_argument("--force-embed", action="store_true", help="Run qmd embed -f after update")
    args = parser.parse_args()

    ensure_dirs()
    maybe_write_auth_json()
    write_soul_file()
    write_hermes_config()
    write_env_file()

    if args.sync_qmd:
        sync_qmd(embed=args.embed, force_embed=args.force_embed)
    elif not args.fast and os.environ.get("HERMES_QMD_UPDATE_ON_BOOT", "").lower() in {"1", "true", "yes"}:
        sync_qmd(embed=False, force_embed=False)


if __name__ == "__main__":
    main()
