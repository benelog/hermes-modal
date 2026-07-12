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

# KakaoTalk message collector store. modal.Dict is shared across containers with
# fresh reads, avoiding the commit/reload staleness a Volume would have between
# the ingest endpoint and the messages endpoint.
KAKAO_DICT_NAME = "kakao-collect"
KAKAO_SCRIPTS_PATH = "/opt/hermes-modal/scripts"
kakao_dict = modal.Dict.from_name(KAKAO_DICT_NAME, create_if_missing=True)

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
    # KakaoTalk collector: Hermes' kakao-room-summary skill reads this to fetch
    # collected messages. Confirm the exact URL from the deploy output and fix
    # if the workspace/label host differs.
    "KAKAO_MESSAGES_URL": "https://benelog--kakao-messages.modal.run",
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


@app.function(
    image=image,
    secrets=[secret],
    timeout=60,
    env=common_env,
)
@modal.fastapi_endpoint(method="POST", label="kakao-ingest")
def kakao_ingest(item: dict, token: str = ""):
    """Receive one scraped KakaoTalk message from the device and store it.

    `token` is a query parameter validated against KAKAO_COLLECTOR_TOKEN.
    `item` is the JSON body: {room, sender, text, ts|client_time}.
    """
    import os
    import sys
    from datetime import datetime, timezone

    from fastapi import HTTPException

    if token != os.environ.get("KAKAO_COLLECTOR_TOKEN", ""):
        raise HTTPException(status_code=401, detail="unauthorized")

    sys.path.insert(0, KAKAO_SCRIPTS_PATH)
    from kakao.collector_core import message_key, normalize_item, plan_ingest

    try:
        rec = normalize_item(item, datetime.now(timezone.utc))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    # Dedupe key excludes sender (heuristic, flips between scrapes). Then merge
    # truncation/edit variants in place against what's stored, so a message is
    # kept once at its earliest received_at (= conversation order) with its
    # fullest text. plan_ingest scans the dict; ingest is low-frequency because
    # the device already dedupes locally before POSTing.
    key = message_key(rec["room"], rec["text"], rec["client_time"])
    plan = plan_ingest(list(kakao_dict.items()), rec, key)
    if plan["action"] == "store":
        kakao_dict[plan["key"]] = rec
    elif plan["action"] == "update":
        kakao_dict[plan["key"]] = plan["rec"]
    # "skip": an equal or fuller copy is already stored — leave it untouched.
    return {"ok": True, "action": plan["action"], "key": plan["key"]}


@app.function(
    image=image,
    secrets=[secret],
    timeout=60,
    env=common_env,
)
@modal.fastapi_endpoint(method="GET", label="kakao-messages")
def kakao_messages(token: str = "", since: str = "1day", room: str = ""):
    """Return collected messages within the `since` window, oldest first.

    Prunes records older than the 14-day retention on each read (best effort).
    """
    import os
    import sys
    from datetime import datetime, timezone

    from fastapi import HTTPException

    if token != os.environ.get("KAKAO_COLLECTOR_TOKEN", ""):
        raise HTTPException(status_code=401, detail="unauthorized")

    sys.path.insert(0, KAKAO_SCRIPTS_PATH)
    from kakao.collector_core import expired_keys, select_messages

    now = datetime.now(timezone.utc)
    items_with_keys = list(kakao_dict.items())

    stale = expired_keys(items_with_keys, now, retention_days=14)
    for key in stale:
        try:
            del kakao_dict[key]
        except KeyError:
            pass

    stale_set = set(stale)
    records = [rec for key, rec in items_with_keys if key not in stale_set]
    selected = select_messages(records, room or None, since, now)
    return {"ok": True, "count": len(selected), "messages": selected}


@app.function(
    image=image,
    secrets=[secret],
    timeout=60,
    env=common_env,
)
@modal.fastapi_endpoint(method="GET", label="kakao-stats")
def kakao_stats(token: str = "", room: str = "", start: str = "", end: str = ""):
    """Per-send-date record counts for one room (transfer verification).

    The collector app calls this right after an explicit backfill run and compares
    the buckets against its local SQLite counts — equal means nothing was lost in
    transmission. `start`/`end` are ISO dates (inclusive) bounding `client_time`.
    """
    import os
    import sys

    from fastapi import HTTPException

    if token != os.environ.get("KAKAO_COLLECTOR_TOKEN", ""):
        raise HTTPException(status_code=401, detail="unauthorized")
    if not room:
        raise HTTPException(status_code=400, detail="room is required")

    sys.path.insert(0, KAKAO_SCRIPTS_PATH)
    from kakao.collector_core import count_by_sent_date

    records = [rec for _key, rec in kakao_dict.items()]
    stats = count_by_sent_date(records, room, start, end)
    return {"ok": True, "room": room, "start": start, "end": end, **stats}


# Max messages fed into one summary prompt. A `since` window on a busy room could
# be large; cap to the most recent N so the prompt stays bounded.
KAKAO_SUMMARY_MAX_MSGS = 600


def _build_summary_prompt(room: str, since: str, messages: list) -> str:
    """Render collected messages + Korean instructions for `hermes -z`.

    The output is posted verbatim back into the KakaoTalk room, so we ask for
    plain text (no markdown) sized for a single group-chat message.
    """
    from datetime import datetime, timedelta, timezone

    kst = timezone(timedelta(hours=9))

    def kst_label(received_at: str) -> str:
        try:
            return datetime.fromisoformat(received_at).astimezone(kst).strftime("%m/%d %H:%M")
        except (ValueError, TypeError):
            return ""

    def sent_label(rec: dict) -> str:
        """발신 시각 라벨: 화면에서 수집한 발신 날짜(+시각)를 우선, 없으면 수집시각(~표시)."""
        date = (rec.get("client_time") or "").strip()
        if len(date) == 10 and date[4] == "-" and date[7] == "-":
            mmdd = f"{date[5:7]}/{date[8:10]}"
            st = (rec.get("sent_time") or "").strip()
            return f"{mmdd} {st}" if st else mmdd
        received = kst_label(rec.get("received_at", ""))
        return f"~{received}" if received else ""

    lines = []
    for rec in messages:
        sender = (rec.get("sender") or "?").strip()
        text = (rec.get("text") or "").strip()
        tlabel = sent_label(rec)
        prefix = f"[{tlabel}] " if tlabel else ""
        lines.append(f"{prefix}{sender}: {text}")
    body = "\n".join(lines)
    return (
        f"너는 카카오톡 그룹 채팅방 '{room}'의 최근 대화를 요약하는 도우미야. "
        f"아래는 최근 {since} 동안의 메시지야(형식: [발신시각 KST] 보낸이: 내용). "
        "발신시각은 MM/DD(날짜만 확인됨) 또는 MM/DD HH:MM(분까지 확인됨) 형식이고, "
        "'~'로 시작하면 발신시각을 몰라 '수집된 시각'으로 대신 적은 것이라 실제 발신은 그보다 이전일 수 있어. "
        "메시지는 발신시각 순으로 정렬돼 있어.\n\n"
        f"{body}\n\n"
        "이 대화를 한국어로 요약해줘. 요약문은 그대로 카카오톡 그룹 방에 전송될 거야. 규칙:\n"
        "- 마크다운 헤더(#)나 굵게(**) 같은 서식 없이 평문으로.\n"
        "- 동일한 본문이 짧은 시간 안에 여러 보낸이로 중복 수집되면 Android 접근성 오수집 가능성이 있다. "
        "특히 한 보낸이가 요청자/내 닉네임처럼 보이는 중복본이면 그 사람이 말했다고 단정하지 말고, 다른 보낸이 또는 중복 수집으로 취급한다.\n"
        "- 맨 앞에 '📚 언급된 책'으로, 대화 중 언급·추천된 책 제목을 먼저 나열한다. "
        "저자·추천한 사람·간단한 맥락이 확인되면 함께 적되, 확인 안 되면 지어내지 말 것. 책이 없으면 이 부분은 생략.\n"
        "- 그 다음 나머지 대화를 주제별로 정리하되, 대화에 등장한 순서대로 적는다.\n"
        "- 각 주제 앞에 그 주제가 처음 등장한 '대략 시각'을 위 [시각] 기준으로 함께 적는다(예: '오후 2시쯤' 또는 '14:20'). "
        "정확한 값이 아니라 대략임을 감안해 너무 정밀하게 단정하지 말고, 시각 정보가 없으면 생략한다(지어내지 말 것).\n"
        "- 대화에 공유된 URL/링크 주소 자체는 본문에 나열하지 마(어떤 맥락에서 공유됐는지만 필요하면 언급). "
        "공유된 링크 목록은 요약 맨 끝에 '🔗 공유된 링크'로 자동으로 덧붙으니 중복해서 적지 말 것.\n"
        "- 그룹 채팅 메시지 한 개에 적당한 길이로 간결하게. 결정/약속/일정(다음 모임, 읽을 범위 등)은 강조.\n"
        "- 메시지에 없는 내용은 지어내지 말 것. 일부 메시지가 누락됐을 수 있으니 단정적 표현은 피할 것.\n"
        "- 요약 본문만 출력하고 다른 말(설명/머리말)은 붙이지 말 것."
    )


@app.function(
    image=image,
    secrets=[secret, github_secret],
    volumes=volume_mounts,
    timeout=60 * 10,
    max_containers=1,
    scaledown_window=60 * 5,
    env=common_env,
)
@modal.fastapi_endpoint(method="POST", label="kakao-summarize")
def kakao_summarize(item: dict, token: str = ""):
    """Summarize a room's recent messages with Hermes' LLM and return the text.

    Body: {"room": str, "command": str}. `command` is the natural-language request
    (e.g. "@정상혁 3일치 요약해줘"); the period is extracted from it. Reuses the
    Modal-resident Hermes model via `hermes -z` (one-shot), so no extra LLM key is
    needed. The device posts the returned `summary` back into the KakaoTalk room.
    """
    import os
    import subprocess
    import sys
    from datetime import datetime, timezone

    from fastapi import HTTPException

    if token != os.environ.get("KAKAO_COLLECTOR_TOKEN", ""):
        raise HTTPException(status_code=401, detail="unauthorized")

    room = (item.get("room") or "").strip()
    command = item.get("command") or ""
    if not room:
        raise HTTPException(status_code=400, detail="room is required")

    sys.path.insert(0, KAKAO_SCRIPTS_PATH)
    from kakao.collector_core import extract_since, extract_urls, select_messages

    since = extract_since(command)
    now = datetime.now(timezone.utc)
    records = [rec for _key, rec in kakao_dict.items()]
    selected = select_messages(records, room, since, now)

    if not selected:
        return {
            "ok": True,
            "count": 0,
            "since": since,
            "summary": f"최근 {since} 동안 '{room}' 방에서 수집된 메시지가 없어요. "
            "(방을 열어 위로 스크롤해야 수집됩니다.)",
        }

    if len(selected) > KAKAO_SUMMARY_MAX_MSGS:
        selected = selected[-KAKAO_SUMMARY_MAX_MSGS:]

    subprocess.run(
        ["python", "/opt/hermes-modal/scripts/prepare_runtime.py", "--fast"],
        check=True,
    )

    prompt = _build_summary_prompt(room, since, selected)
    try:
        proc = subprocess.run(
            ["hermes", "-z", prompt],
            capture_output=True,
            text=True,
            env=os.environ.copy(),
            cwd=HERMES_HOME,
            timeout=60 * 7,
        )
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="summarize timed out")

    summary = proc.stdout.strip()
    if proc.returncode != 0 or not summary:
        raise HTTPException(
            status_code=502,
            detail=f"summarize failed rc={proc.returncode}: {proc.stderr.strip()[-500:]}",
        )

    # Append every shared link deterministically so none is dropped by the LLM.
    urls = extract_urls(selected)
    if urls:
        summary = summary + "\n\n🔗 공유된 링크\n" + "\n".join(urls)

    return {"ok": True, "count": len(selected), "since": since, "summary": summary}
