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
SKILLS_SOURCE = Path(__file__).with_name("skills")
HOOKS_SOURCE = Path(__file__).with_name("hooks")
CRON_SCRIPTS_SOURCE = Path(__file__).with_name("cron")
IMAGE_MANIFEST_NAME = ".image-manifest.txt"


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
    """Bootstrap auth.json from the Modal Secret on a fresh volume.

    Hermes rotates the OAuth refresh token at runtime, so the volume's copy
    diverges from the secret almost immediately. Overwriting on every container
    start would clobber the rotated tokens and invalidate them with the OAuth
    server. Bootstrap once, then leave alone. Use `bootstrap_auth` in
    modal_app.py to force a reseed after re-authenticating.
    """
    encoded = os.environ.get("HERMES_AUTH_JSON_B64", "").strip()
    if not encoded:
        return
    target = HERMES_HOME / "auth.json"
    if target.exists():
        return
    target.write_bytes(base64.b64decode(encoded))
    target.chmod(0o600)
    print(f"wrote {target} (bootstrap)")


def write_soul_file() -> None:
    if not SOUL_SOURCE.exists():
        return
    target = HERMES_HOME / "SOUL.md"
    content = SOUL_SOURCE.read_text(encoding="utf-8")
    if target.exists() and target.read_text(encoding="utf-8", errors="ignore") == content:
        return
    target.write_text(content, encoding="utf-8")
    print(f"wrote {target}")


def sync_image_dir(src: Path, dst: Path) -> None:
    """Mirror an image-supplied directory into a HERMES_HOME location.

    The image is the source of truth for files shipped from git. To allow
    Hermes to add new files at runtime without losing them on the next
    container start, image-supplied files are tracked in a manifest. Files
    that were in the previous manifest but are no longer in the image are
    removed; runtime-only files are preserved.
    """
    if not src.exists():
        return
    dst.mkdir(parents=True, exist_ok=True)
    manifest_path = dst / IMAGE_MANIFEST_NAME

    new_manifest: set[str] = set()
    for src_file in src.rglob("*"):
        if not src_file.is_file():
            continue
        rel = src_file.relative_to(src).as_posix()
        new_manifest.add(rel)
        target = dst / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        content = src_file.read_bytes()
        if target.exists() and target.read_bytes() == content:
            continue
        target.write_bytes(content)
        print(f"wrote {target}")

    old_manifest: set[str] = set()
    if manifest_path.exists():
        old_manifest = {line.strip() for line in manifest_path.read_text(encoding="utf-8").splitlines() if line.strip()}

    for rel in sorted(old_manifest - new_manifest):
        target = dst / rel
        if not target.exists():
            continue
        target.unlink()
        print(f"removed {target} (no longer in image)")
        parent = target.parent
        while parent != dst and parent.exists() and not any(parent.iterdir()):
            parent.rmdir()
            parent = parent.parent

    manifest_path.write_text("\n".join(sorted(new_manifest)) + "\n", encoding="utf-8")


def sync_skills_dir() -> None:
    sync_image_dir(SKILLS_SOURCE, HERMES_HOME / "skills")


def sync_hooks_dir() -> None:
    sync_image_dir(HOOKS_SOURCE, HERMES_HOME / "hooks")


def sync_cron_scripts_dir() -> None:
    sync_image_dir(CRON_SCRIPTS_SOURCE, HERMES_HOME / "scripts")


def patch_hermes_skill_command_path_filter() -> None:
    """Patch Hermes' Telegram skill menu filter for Modal volume paths.

    Hermes Agent v0.12.0 compares raw skill paths from get_skill_commands()
    against SKILLS_DIR.resolve(). In Modal, /root/.hermes/skills resolves to a
    /__modal/volumes/... path, while get_skill_commands() returns the raw
    /root/.hermes/skills/... path. The raw string prefix comparison therefore
    drops every skill command from Telegram's menu, including /english.
    """
    try:
        import hermes_cli.commands as commands
    except Exception as exc:
        print(f"skipped Hermes skill command path patch: import failed: {exc}")
        return

    target = Path(commands.__file__)
    try:
        text = target.read_text(encoding="utf-8")
    except Exception as exc:
        print(f"skipped Hermes skill command path patch: read failed: {exc}")
        return

    marker = "# Modal path-resolution patch for skill command menu"
    if marker in text:
        return

    old = '''            skill_path = info.get("skill_md_path", "")
            if not skill_path.startswith(_skills_dir):
                continue
            if skill_path.startswith(_hub_dir):
                continue
'''
    new = '''            skill_path_raw = info.get("skill_md_path", "")
            # Modal path-resolution patch for skill command menu: compare
            # resolved paths because /root/.hermes/skills may be backed by a
            # /__modal/volumes/... mount while skill_commands stores raw paths.
            try:
                skill_path = str(__import__("pathlib").Path(skill_path_raw).resolve())
            except Exception:
                skill_path = str(skill_path_raw)
            if not skill_path.startswith(_skills_dir):
                continue
            if skill_path.startswith(_hub_dir):
                continue
'''
    if old not in text:
        print("skipped Hermes skill command path patch: target block not found")
        return

    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {target} for Modal skill command menu paths")


def patch_openai_parse_response_none_output() -> None:
    """Coerce a missing Response.output to [] in openai.lib._parsing._responses.

    The ChatGPT Codex backend (chatgpt.com/backend-api/codex) can emit a
    `response.completed` SSE event whose payload has `output=null`. The OpenAI
    SDK's parse_response() does `for output in response.output:` with no None
    guard, so the whole stream raises TypeError mid-event. hermes-agent already
    has a safety net at run_agent.py:5197 that re-synthesizes the response from
    accumulated text deltas when output is an empty list — but it never runs
    because the SDK crashes first. This patch flips None to [] so the
    downstream backfill path can fire.
    """
    try:
        from openai.lib._parsing import _responses as openai_parse_responses
    except Exception as exc:
        print(f"skipped openai parse_response None-output patch: import failed: {exc}")
        return

    target = Path(openai_parse_responses.__file__)
    try:
        text = target.read_text(encoding="utf-8")
    except Exception as exc:
        print(f"skipped openai parse_response None-output patch: read failed: {exc}")
        return

    marker = "# Modal patch: tolerate None Response.output"
    if marker in text:
        return

    old = "    for output in response.output:"
    new = (
        "    # Modal patch: tolerate None Response.output from chatgpt.com codex backend\n"
        "    for output in (response.output or []):"
    )
    if old not in text:
        print("skipped openai parse_response None-output patch: target line not found")
        return

    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {target} to tolerate None Response.output")


def patch_hermes_non_retryable_traceback() -> None:
    """Add exc_info=True to the non-retryable client error log.

    hermes-agent logs `Non-retryable client error: ...` at ERROR level with no
    traceback (only the DEBUG-level outer-loop handler captures exc_info).
    Without the traceback we can't locate the actual TypeError/ValueError that
    triggered the abort path. This patch flips exc_info on so Modal logs show
    the full stack trace on the next failure.
    """
    try:
        import run_agent
    except Exception as exc:
        print(f"skipped Hermes non-retryable traceback patch: import failed: {exc}")
        return

    target = Path(run_agent.__file__)
    try:
        text = target.read_text(encoding="utf-8")
    except Exception as exc:
        print(f"skipped Hermes non-retryable traceback patch: read failed: {exc}")
        return

    marker = "# Modal patch: surface traceback for non-retryable client errors"
    if marker in text:
        return

    old = '                        logging.error(f"{self.log_prefix}Non-retryable client error: {api_error}")'
    new = (
        '                        # Modal patch: surface traceback for non-retryable client errors\n'
        '                        logging.error(f"{self.log_prefix}Non-retryable client error: {api_error}", exc_info=True)'
    )
    if old not in text:
        print("skipped Hermes non-retryable traceback patch: target line not found")
        return

    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {target} to log traceback on non-retryable client errors")


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
        "quick_commands": {
            "english": {
                "type": "exec",
                "command": str(HERMES_HOME / "scripts" / "run_english_cron.py"),
            },
        },
        "security": {
            "tirith_enabled": False,
            "redact_secrets": True,
        },
        "quick_commands": {
            "english": {
                "type": "exec",
                "command": "(HERMES_ACCEPT_HOOKS=1 /usr/local/bin/hermes cron run 157db4a4b6c3 && HERMES_ACCEPT_HOOKS=1 /usr/local/bin/hermes cron tick) >/tmp/hermes-english-cron-trigger.log 2>&1 & echo '영어학습 cronjob 실행을 요청했습니다. 잠시 후 결과가 이 채팅으로 도착합니다.'",
            },
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
    sync_skills_dir()
    sync_hooks_dir()
    sync_cron_scripts_dir()
    patch_hermes_skill_command_path_filter()
    patch_hermes_non_retryable_traceback()
    patch_openai_parse_response_none_output()
    write_hermes_config()
    write_env_file()

    if args.sync_qmd:
        sync_qmd(embed=args.embed, force_embed=args.force_embed)
    elif not args.fast and os.environ.get("HERMES_QMD_UPDATE_ON_BOOT", "").lower() in {"1", "true", "yes"}:
        sync_qmd(embed=False, force_embed=False)


if __name__ == "__main__":
    main()
