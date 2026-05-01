"""Modal deployment for Hermes Telegram gateway with qmd MCP.

This app exposes Hermes' built-in Telegram webhook server via Modal's
@web_server integration. It also provides a sync_qmd function to clone/pull the
knowledge repos, write qmd collection config, and build/update the qmd index.

Deploy flow:
  1. modal deploy modal_app.py
  2. Read the Modal URL from deploy output.
  3. Add TELEGRAM_WEBHOOK_URL=<that URL>/telegram to the Modal secret.
  4. modal run modal_app.py::sync_qmd --embed
  5. modal deploy modal_app.py
"""

from __future__ import annotations

import modal

APP_NAME = "hermes-telegram-gateway"
SECRET_NAME = "hermes-modal-secrets"
WEBHOOK_PORT = 8443

HERMES_HOME = "/root/.hermes"
WORKSPACE_DIR = "/root/workspace"
QMD_CACHE_DIR = "/root/.cache/qmd"
QMD_CONFIG_DIR = "/root/.config/qmd"

hermes_volume = modal.Volume.from_name("hermes-home", create_if_missing=True)
workspace_volume = modal.Volume.from_name("hermes-workspace", create_if_missing=True)
qmd_cache_volume = modal.Volume.from_name("qmd-cache", create_if_missing=True)
qmd_config_volume = modal.Volume.from_name("qmd-config", create_if_missing=True)

# Keep these package versions close to the currently-working local setup.
image = (
    modal.Image.debian_slim(python_version="3.11")
    .apt_install("bash", "ca-certificates", "curl", "git", "openssh-client")
    .run_commands(
        "curl -fsSL https://deb.nodesource.com/setup_22.x | bash -",
        "apt-get install -y nodejs",
        "npm install -g kqmd@2.1.0-kqmd.1",
    )
    .pip_install(
        "hermes-agent[messaging,mcp,cron,cli] @ git+https://github.com/NousResearch/hermes-agent.git",
        "modal>=1.0.0,<2",
    )
    .add_local_dir("scripts", remote_path="/opt/hermes-modal/scripts")
)

app = modal.App(APP_NAME)
secret = modal.Secret.from_name(SECRET_NAME)
github_secret = modal.Secret.from_name("github-secret")

volume_mounts = {
    HERMES_HOME: hermes_volume,
    WORKSPACE_DIR: workspace_volume,
    QMD_CACHE_DIR: qmd_cache_volume,
    QMD_CONFIG_DIR: qmd_config_volume,
}

common_env = {
    "HERMES_HOME": HERMES_HOME,
    "QMD_CONFIG_DIR": QMD_CONFIG_DIR,
    "TELEGRAM_WEBHOOK_PORT": str(WEBHOOK_PORT),
    "TELEGRAM_WEBHOOK_URL": "https://benelog--hermes-telegram-gateway-gateway.modal.run/telegram",
    # Modal containers do not need Telegram fallback IP hacking; the default
    # api.telegram.org route is cleaner in the cloud.
    "HERMES_TELEGRAM_DISABLE_FALLBACK_IPS": "1",
    # Use qmd's local GGUF models on CPU unless you override via secret/env.
    "QMD_LLAMA_GPU": "false",
    # Force prepare_runtime.py to rewrite config.yaml on each container start
    # so changes in scripts/prepare_runtime.py reach the deployed runtime.
    "HERMES_MODAL_OVERWRITE_CONFIG": "1",
}


@app.function(
    image=image,
    secrets=[secret, github_secret],
    volumes=volume_mounts,
    timeout=60 * 60,
    max_containers=1,
    scaledown_window=60 * 10,
    env=common_env,
)
@modal.concurrent(max_inputs=50)
@modal.web_server(WEBHOOK_PORT, startup_timeout=120)
def gateway():
    """Run Hermes gateway and expose Telegram's webhook endpoint.

    If TELEGRAM_WEBHOOK_URL is not set yet, this starts a tiny placeholder HTTP
    server. That lets the first `modal deploy` succeed so you can copy the Modal
    URL and add TELEGRAM_WEBHOOK_URL to the secret before the real deployment.
    """
    import os
    import subprocess

    subprocess.run(
        ["python", "/opt/hermes-modal/scripts/prepare_runtime.py", "--fast"],
        check=True,
    )

    if not os.environ.get("TELEGRAM_WEBHOOK_URL", "").strip():
        print(
            "TELEGRAM_WEBHOOK_URL is not set; starting placeholder server on "
            f"0.0.0.0:{WEBHOOK_PORT}. Add TELEGRAM_WEBHOOK_URL=<modal-url>/telegram "
            "to the Modal secret, then redeploy."
        )
        subprocess.Popen(
            ["python", "-m", "http.server", str(WEBHOOK_PORT), "--bind", "0.0.0.0"],
        )
        return

    subprocess.Popen(
        [
            "bash",
            "-lc",
            "while true; do hermes gateway run --replace; rc=$?; echo hermes gateway exited with $rc; sleep 5; done",
        ],
        env=os.environ.copy(),
    )


@app.function(
    image=image,
    secrets=[secret, github_secret],
    volumes=volume_mounts,
    timeout=60 * 60 * 4,
    env=common_env,
)
def sync_qmd(embed: bool = False, force_embed: bool = False):
    """Clone/pull knowledge repos and update qmd index inside Modal volumes."""
    import subprocess

    args = ["python", "/opt/hermes-modal/scripts/prepare_runtime.py", "--sync-qmd"]
    if embed:
        args.append("--embed")
    if force_embed:
        args.append("--force-embed")
    subprocess.run(args, check=True)

    hermes_volume.commit()
    workspace_volume.commit()
    qmd_cache_volume.commit()
    qmd_config_volume.commit()
    return "qmd sync complete"


@app.function(
    image=image,
    secrets=[secret, github_secret],
    volumes=volume_mounts,
    timeout=60 * 10,
    env=common_env,
)
def commit_skills():
    """Manually push runtime skill changes from the volume to git.

    Run with `modal run modal_app.py::commit_skills`. The same script is also
    invoked automatically by the `skills-autocommit` Hermes hook on every
    `agent:end` event, so manual triggering is normally only needed when
    debugging.
    """
    import subprocess

    hermes_volume.reload()
    result = subprocess.run(
        ["python", "/opt/hermes-modal/scripts/commit_skills.py"],
        capture_output=True,
        text=True,
    )
    return f"exit={result.returncode}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"


@app.function(
    image=image,
    secrets=[secret, github_secret],
    volumes=volume_mounts,
    timeout=60 * 10,
    env=common_env,
)
def doctor():
    """Return a compact status report from the Modal runtime."""
    import subprocess

    subprocess.run(["python", "/opt/hermes-modal/scripts/prepare_runtime.py", "--fast"], check=True)
    out = subprocess.check_output(
        "set -e; hermes status --all; printf '\\n--- qmd ---\\n'; qmd status; printf '\\n--- mcp ---\\n'; hermes mcp list",
        shell=True,
        text=True,
        stderr=subprocess.STDOUT,
        timeout=540,
    )
    return out
