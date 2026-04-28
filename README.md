# Hermes on Modal with Telegram Webhook + qmd

**목표**: 개인 지식 DB로 바탕으로 대답하는 Telegram 챗봇을 **비용 0원**으로 서빙하기.

- 지식 소스는 GitHub에서 Markdown, Asciidoc으로 저장
    * 인덱싱 대상은 [`scripts/qmd_repos.yml`](scripts/qmd_repos.yml)에 정의
- Modal container는 Telegram 메시지가 올 때만 켜졌다가 유휴 시 자동 종료
    - Modal free tier 한도 활용을 목표로 (`min_containers` 미지정 + `scaledown_window=600`).
    - Hermes(LLM agent) + qmd(GitHub repo 임베딩 검색 MCP) + Telegram webhook을 한 컨테이너에  띄움

## 파일 구성

- `modal_app.py`
  - Modal app 정의
  - `gateway`: Telegram webhook을 받는 Hermes gateway web server
  - `sync_qmd`: repo clone/pull + qmd update/embed
  - `commit_skills`: Modal volume에 쌓인 runtime skill 변경을 git으로 push (Hermes `agent:end` hook이 자동 호출, 수동 디버깅용으로도 사용)
  - `doctor`: Modal runtime에서 Hermes/qmd 상태 확인
- `scripts/prepare_runtime.py`
  - Modal volume 안의 Hermes config, `.env`, qmd config, repo clone/pull을 준비
  - `HERMES_MODAL_OVERWRITE_CONFIG=1`이면 컨테이너 시작마다 config.yaml을 다시 씀
- `scripts/qmd_repos.yml`
  - 로컬 qmd collection이 참조하는 git repo 목록
  - Modal에서는 이 manifest를 기준으로 repo root를 clone/pull하고, collection path만 qmd config에 연결
- `scripts/create_modal_secret.py`
  - 로컬 `~/.hermes/.env`, `~/.hermes/auth.json`에서 Modal secret을 생성/갱신
- `scripts/commit_skills.py`
  - Modal volume(`/root/.hermes/skills`)의 변경분을 이 repo의 `scripts/skills/`로 복사 후 git push
- `scripts/SOUL.md`
  - Modal runtime의 `~/.hermes/SOUL.md`로 배포되는 Hermes persona/지침 파일
- `scripts/hooks/`, `scripts/skills/`
  - Hermes hook, skill 정의. `prepare_runtime.py`가 컨테이너 부팅 시 `~/.hermes` 아래로 동기화

## 0. 보안 주의

Modal의 `sync_qmd`는 `scripts/qmd_repos.yml`에 있는 모든 repo를 `/root/workspace/benelog/<collection-name>` 아래로 `git clone` 또는 `git pull --ff-only` 합니다. 그런 다음 qmd config에는 repo 내부의 collection path만 연결합니다.

Modal volume에 저장된 데이터는 Hermes/qmd 컨테이너에서 그대로 읽힙니다. Hermes auth token, GitHub token 등 secret은 Modal Secret으로만 주입하고, repo 본문에 commit하지 마세요.

private repo 접근은 둘 중 하나가 필요합니다.

1. `GITHUB_TOKEN`
   - private repo read 권한이 있는 fine-grained token 추천
2. `GIT_SSH_PRIVATE_KEY`
   - GitHub deploy key 또는 별도 read-only key

- `scripts/create_modal_secret.py`는 로컬 `.env`에 있는 `GITHUB_TOKEN` 또는 환경변수의 `GITHUB_TOKEN`을 복사합니다.
- GitHub token을 별도 Modal Secret으로 관리한다면 secret 이름은 `github-secret`, key 이름은 `GITHUB_TOKEN`으로 두세요. `modal_app.py`는 `hermes-modal-secrets`와 `github-secret`을 함께 주입합니다.
현재 로컬 `.env`에는 GitHub token이 없으므로, private repo에 대한 인덱싱을 Local에서 테스트하려면 아래처럼 실행 전에 넣어야 합니다.

```bash
export GITHUB_TOKEN='github_pat_...'
```

## 1. Modal CLI 설치/로그인

```bash
python3 -m pip install --user 'modal>=1.0.0,<2'
modal setup
```

이미 Modal CLI가 있으면 설치는 생략하세요.

## 2. 1차 secret 생성

아직 Modal URL을 모르므로 webhook URL 없이 secret을 먼저 만듭니다.
이 단계에서는 Hermes가 실제로 Telegram을 받지 않고 placeholder server로 뜹니다.

```bash
python3 scripts/create_modal_secret.py
```

## 3. 1차 deploy로 Modal URL 확보

```bash
modal deploy modal_app.py
```

출력에 `https://...modal.run` URL이 나옵니다.
그 URL 뒤에 `/telegram`을 붙인 것이 Telegram webhook URL입니다.

예:

```text
https://YOUR-WORKSPACE--hermes-telegram-gateway-gateway.modal.run/telegram
```

## 4. webhook URL 반영

현재 배포 URL은 `modal_app.py`의 `common_env`에 직접 반영되어 있습니다. GitHub token을 Modal UI에서 직접 넣은 경우, `scripts/create_modal_secret.py --webhook-url ...`를 다시 실행하면 UI에서 추가한 secret key가 덮어써질 수 있으므로 실행하지 마세요.

현재 webhook URL:

```text
https://benelog--hermes-telegram-gateway-gateway.modal.run/telegram
```

## 5. qmd 인덱스 준비

처음 한 번은 embed까지 실행하는 것을 추천합니다.
qmd는 로컬 GGUF embedding/rerank 모델을 Modal volume에 다운로드할 수 있어서 시간이 걸릴 수 있습니다.

```bash
modal run modal_app.py::sync_qmd --embed
```

인덱스가 꼬였거나 모델/청킹 설정을 바꾼 뒤에는:

```bash
modal run modal_app.py::sync_qmd --force-embed
```

평소 문서 repo만 업데이트할 때는:

```bash
modal run modal_app.py::sync_qmd
```

## 6. 최종 deploy

```bash
modal deploy modal_app.py
```

이제 Hermes가 Modal에서 Telegram webhook mode로 떠야 합니다.

## 7. 로컬 gateway 중지

Telegram bot token은 webhook/polling을 동시에 안정적으로 쓸 수 없습니다.
Modal 전환 후 로컬 gateway들은 내려주세요.

```bash
systemctl --user stop hermes-gateway.service || true
systemctl --user stop openclaw-gateway.service || true
```

필요하면 자동 시작도 끕니다.

```bash
systemctl --user disable hermes-gateway.service || true
systemctl --user disable openclaw-gateway.service || true
```

## 8. 상태 확인

```bash
modal run modal_app.py::doctor
```

Telegram에서 봇에게 메시지를 보내서 end-to-end로 확인합니다.

## 운영 메모

- `min_containers`는 지정하지 않았습니다. Telegram 메시지가 들어올 때만 container가 켜지고, 유휴 상태가 `scaledown_window`(현재 600초) 동안 이어지면 자동 종료됩니다. 24/7 과금을 피하려는 설정입니다.
- 첫 메시지는 cold start로 몇 초~십수 초 늦어질 수 있습니다. 응답 지연이 부담되면 `min_containers=1`을 다시 추가하면 되지만, 그러면 사용량과 무관하게 매월 고정 비용이 발생합니다.
- `max_containers=1`로 Telegram bot 중복 실행을 막았습니다. 동시 webhook 처리는 `@modal.concurrent(max_inputs=50)`으로 한 컨테이너 안에서 받습니다.
- Modal volume 4개를 사용합니다: `hermes-home`, `hermes-workspace`, `qmd-cache`, `qmd-config`. 첫 deploy 때 자동 생성됩니다.
- qmd 문서 업데이트는 cron/GitHub Actions에서 `modal run modal_app.py::sync_qmd`를 주기적으로 호출하는 방식이 깔끔합니다.
- qmd embedding/rerank는 `QMD_LLAMA_GPU=false`로 CPU에서 돕니다. 속도가 부족하면 GPU로 바꾸세요.
- runtime에서 만들어진 skill은 `agent:end` hook이 `commit_skills` function을 호출해 이 repo에 자동 push합니다. 최근 커밋의 `Sync skills from Modal volume`이 그 결과입니다.
- 현재 `HERMES_MODEL_PROVIDER=openai-codex` + `HERMES_AUTH_JSON_B64` 복사 방식입니다. OAuth 토큰 만료/갱신 이슈가 생기면 OpenRouter/Anthropic/API-key provider로 바꾸는 편이 더 안정적입니다.

## 문제 해결

### Telegram webhook secret 오류

Hermes가 다음과 유사하게 실패하면:

```text
TELEGRAM_WEBHOOK_SECRET is required when TELEGRAM_WEBHOOK_URL is set
```

`create_modal_secret.py`를 다시 실행해 secret을 갱신하세요.

### qmd가 collection을 못 찾음

```bash
modal run modal_app.py::sync_qmd
modal run modal_app.py::doctor
```

`doctor` 출력의 qmd collection path가 `/root/workspace/benelog/...`인지 확인하세요.

### private repo clone 실패

`GITHUB_TOKEN` 또는 `GIT_SSH_PRIVATE_KEY`가 Modal secret에 들어갔는지 확인하세요.

```bash
python3 scripts/create_modal_secret.py --webhook-url 'https://...modal.run/telegram'
```

### 로컬과 Modal이 동시에 응답함

로컬 systemd service를 끄세요.

```bash
systemctl --user stop hermes-gateway.service openclaw-gateway.service
```

### runtime skill 변경이 repo에 안 올라옴

`agent:end` hook이 동작하지 않으면 직접 호출해서 push 가능 여부를 확인합니다.

```bash
modal run modal_app.py::commit_skills
```

출력의 `exit=` 값과 `stderr`를 보고 SSH key/GitHub token 권한을 점검하세요.

### 컨테이너 시작 시 config가 갱신되지 않음

`prepare_runtime.py` 변경이 반영되지 않으면 `HERMES_MODAL_OVERWRITE_CONFIG=1`이 secret/env에 들어가 있는지 확인하세요. `modal_app.py`의 `common_env` 기본값이지만 secret으로 덮어써졌을 수 있습니다.
