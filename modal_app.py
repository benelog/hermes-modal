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
        "fastapi[standard]",
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
    timeout=60 * 60,
    max_containers=1,
    schedule=modal.Cron("0 7,19 * * *", timezone="Asia/Seoul"),
    env=common_env,
)
def cron_tick():
    """Wake the Modal app every morning/evening and run due Hermes cron jobs.

    Hermes' built-in scheduler only ticks while the gateway process is running.
    This Modal schedule keeps the Telegram webhook server scale-to-zero friendly:
    Modal wakes a short-lived container at 07:00 and 19:00 KST, runs
    `hermes cron tick`, persists cron state/output back to the Hermes volume,
    and exits.
    """
    import subprocess

    hermes_volume.reload()
    subprocess.run(
        ["python", "/opt/hermes-modal/scripts/prepare_runtime.py", "--fast"],
        check=True,
    )
    result = subprocess.run(
        ["hermes", "cron", "tick"],
        capture_output=True,
        text=True,
    )
    hermes_volume.commit()
    result.check_returncode()
    return f"exit={result.returncode}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"


# ---------------------------------------------------------------- 서점 순위 추적
# 위키북스 「AI 에이전트와 스프링 배치를 활용한 배치 엔지니어링」의 YES24·교보문고·알라딘
# 순위를 매일 07:00 KST에 수집해 Telegram으로 보내고, 날짜별 표 페이지를 제공한다.
# Hermes 런타임이 필요 없어 표준 라이브러리 + fastapi만 든 가벼운 이미지를 쓴다.
# 이력은 Volume에 둔다(Modal Dict는 7일 미접근 시 만료되므로 장기 보관에 부적합).
BOOK_RANK_DIR = "/data/book-rank"
BOOK_RANK_HISTORY = f"{BOOK_RANK_DIR}/history.json"
BOOK_RANK_PAGE_URL = "https://benelog--book-rank.modal.run"  # book_rank_page의 label 기반 주소
book_rank_volume = modal.Volume.from_name("book-rank", create_if_missing=True)
book_rank_image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("fastapi[standard]")
    .add_local_file("scripts/book_rank.py", remote_path="/root/book_rank.py")
)


@app.function(
    image=book_rank_image,
    secrets=[secret],
    volumes={BOOK_RANK_DIR: book_rank_volume},
    timeout=60 * 5,
    max_containers=1,
    schedule=modal.Cron("0 7 * * *", timezone="Asia/Seoul"),
)
def book_rank_tick(notify: bool = True):
    """서점 3곳의 순위를 수집·저장하고 Telegram으로 보고한다.

    수동 실행: `modal run modal_app.py::book_rank_tick` (같은 날짜는 덮어씀).
    """
    import os
    from datetime import datetime
    from pathlib import Path
    from zoneinfo import ZoneInfo

    import book_rank

    now = datetime.now(ZoneInfo("Asia/Seoul"))
    today = now.strftime("%Y-%m-%d")
    path = Path(BOOK_RANK_HISTORY)

    book_rank_volume.reload()
    results = book_rank.collect_all()
    records = book_rank.upsert_record(
        book_rank.load_history(path), today, results, now.isoformat(timespec="seconds")
    )
    book_rank.save_history(path, records)
    book_rank_volume.commit()

    # `modal run`(임시 앱)에서도 배포본 주소가 나가도록 고정 URL을 쓴다.
    message = book_rank.format_telegram_message(records, today, BOOK_RANK_PAGE_URL)
    print(message)

    if notify:
        token = os.environ["TELEGRAM_BOT_TOKEN"]
        # 별도 지정이 없으면 허용 사용자 목록의 첫 사용자(개인 DM)로 보낸다.
        chat_id = os.environ.get("BOOK_RANK_CHAT_ID", "").strip() or (
            os.environ["TELEGRAM_ALLOWED_USERS"].split(",")[0].strip()
        )
        book_rank.send_telegram(token, chat_id, message)
    return message


@app.function(
    image=book_rank_image,
    volumes={BOOK_RANK_DIR: book_rank_volume},
    timeout=60,
    max_containers=1,
)
def book_rank_manual(text: str) -> str:
    """메신저에서 확인한 교보문고 순위를 기록하고 갱신된 요약을 돌려준다.

    교보 자동 수집(직접 + Jina Reader 우회)이 실패한 날 사람이 확인해 넣는다.
    Telegram `/kyobo <입력>` 플러그인(scripts/plugins/book-rank)이 호출한다.
    예: "117", "컴퓨터/IT 117위", "2026-11-18 컴퓨터/IT 117", "없음"
    """
    from datetime import datetime
    from pathlib import Path
    from zoneinfo import ZoneInfo

    import book_rank

    now = datetime.now(ZoneInfo("Asia/Seoul"))
    path = Path(BOOK_RANK_HISTORY)
    book_rank_volume.reload()
    records = book_rank.load_history(path)
    try:
        date, result = book_rank.parse_manual_input(
            text, book_rank.last_category(records, "kyobo")
        )
    except ValueError as exc:
        return f"⚠️ {exc}"
    date = date or now.strftime("%Y-%m-%d")
    existing = next((r for r in records if r["date"] == date), None)
    fetched_at = existing["fetched_at"] if existing else now.isoformat(timespec="seconds")
    records = book_rank.upsert_record(records, date, {"kyobo": result}, fetched_at)
    book_rank.save_history(path, records)
    book_rank_volume.commit()
    return "✅ 교보문고 순위를 기록했어요.\n\n" + book_rank.format_telegram_message(
        records, date, BOOK_RANK_PAGE_URL
    )


@app.function(
    image=book_rank_image,
    volumes={BOOK_RANK_DIR: book_rank_volume},
    timeout=30,
    max_containers=1,
    scaledown_window=60,
)
@modal.fastapi_endpoint(method="GET", label="book-rank")
def book_rank_page():
    """날짜별 서점 순위 표(HTML)."""
    from pathlib import Path

    from fastapi.responses import HTMLResponse

    import book_rank

    book_rank_volume.reload()
    records = book_rank.load_history(Path(BOOK_RANK_HISTORY))
    return HTMLResponse(book_rank.render_html(records))


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
    timeout=60,
    env=common_env,
)
def bootstrap_auth():
    """Force-rewrite the volume's auth.json from HERMES_AUTH_JSON_B64.

    prepare_runtime.py only bootstraps auth.json when the volume copy is
    missing, so rotated tokens survive restarts. After re-authenticating and
    refreshing the Modal Secret, call this to reseed the volume with the new
    credentials.
    """
    import base64
    import os
    from pathlib import Path

    encoded = os.environ.get("HERMES_AUTH_JSON_B64", "").strip()
    if not encoded:
        raise RuntimeError("HERMES_AUTH_JSON_B64 missing from Modal Secret")
    target = Path(HERMES_HOME) / "auth.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(base64.b64decode(encoded))
    target.chmod(0o600)
    hermes_volume.commit()
    return f"wrote {target}"


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
