# Hermes on Modal with Telegram Webhook + kqmd

**목표**: 개인 지식 DB로 바탕으로 대답하는 Telegram 챗봇을 **비용 0원**으로 서빙하기.

- 지식 소스는 GitHub에서 Markdown, Asciidoc으로 저장
    * 인덱싱 대상은 [`scripts/qmd_repos.yml`](scripts/qmd_repos.yml)에 정의
- Modal container는 Telegram 메시지가 올 때만 켜졌다가 유휴 시 자동 종료
    - Modal free tier 한도 활용을 목표로 (`min_containers` 미지정 + `scaledown_window=600`).
    - Hermes(LLM agent) + [kqmd](https://github.com/jylkim/kqmd)(GitHub repo 임베딩 검색 MCP, qmd의 한글 형태소 분석 강화 fork) + Telegram webhook을 한 컨테이너에  띄움

## 파일 구성

- `modal_app.py`
  - Modal app 정의
  - `gateway`: Telegram webhook을 받는 Hermes gateway web server
  - `sync_qmd`: repo clone/pull + qmd update/embed
  - `commit_skills`: Modal volume에 쌓인 runtime skill 변경을 git으로 push (Hermes `agent:end` hook이 자동 호출, 수동 디버깅용으로도 사용)
  - `doctor`: Modal runtime에서 Hermes/qmd 상태 확인
  - `book_rank_tick`: 매일 07:00 KST에 「AI 에이전트와 스프링 배치를 활용한 배치 엔지니어링」의 YES24·교보문고·알라딘 순위(부문 포함)를 수집해 Telegram으로 보고. 전날 대비 변동과 집계 부문 변경을 표시. 수동 실행은 `modal run modal_app.py::book_rank_tick`
  - `book_rank_manual`: 교보문고 순위 수동 기록. Telegram에서 `/kyobo 117`, `/kyobo 컴퓨터/IT 117위`, `/kyobo 2026-11-18 컴퓨터/IT 117`, `/kyobo 없음`으로 호출(`scripts/plugins/book-rank` 플러그인 → 이 함수). 같은 날 자동 수집이 실패해도 수동 값은 덮어쓰지 않음
  - `book_rank_page`: 날짜별 순위 표 웹 페이지(`https://benelog--book-rank.modal.run`). 이력은 Volume `book-rank`의 `history.json`
- `scripts/book_rank.py`
  - 서점 순위 수집·파싱·이력·메시지·HTML 렌더링(표준 라이브러리만 사용). 대상 도서/서점 URL은 파일 상단 `STORES`
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
- `scripts/plugins/`
  - Hermes 사용자 플러그인. `prepare_runtime.py`가 `~/.hermes/plugins`로 동기화하고 config의 `plugins.enabled`로 켬
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

- `GITHUB_TOKEN`은 별도 Modal Secret `github-secret`에 두세요. `modal_app.py`가 `hermes-modal-secrets`와 `github-secret`을 함께 주입합니다.
- `scripts/create_modal_secret.py`는 의도적으로 `GITHUB_TOKEN`을 건드리지 않습니다. `modal secret create --force`가 secret 전체를 교체하기 때문에, 만약 이 스크립트가 token을 넣고 빠뜨리는 경우가 생기면 UI에서 추가한 `github-secret`을 덮어쓸 위험이 있습니다.
- private repo에 대한 인덱싱을 로컬에서 테스트만 하려면 환경변수만 임시로 주입하세요.

```bash
export GITHUB_TOKEN='github_pat_...'
```

Modal에 처음 `github-secret`을 만들 때:

```bash
modal secret create github-secret GITHUB_TOKEN='github_pat_...'
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
- Modal volume 5개를 사용합니다: `hermes-home`, `hermes-workspace`, `qmd-cache`, `qmd-config`, `book-rank`. 첫 deploy 때 자동 생성됩니다.
- 서점 순위 보고는 `TELEGRAM_ALLOWED_USERS`의 첫 사용자에게 DM으로 갑니다. 다른 채팅으로 보내려면 Modal Secret에 `BOOK_RANK_CHAT_ID`를 추가하세요. 교보문고는 UA만 브라우저로 바꾸면 빈 응답을 주므로 `book_rank.py`의 `BROWSER_HEADERS`(Sec-Fetch-* 등)를 함께 보냅니다. 그래도 Modal(클라우드) IP에는 빈 응답을 주므로, 그때는 Jina Reader(`r.jina.ai`, 무료·무키)로 다시 받아 오되 `X-Target-Selector`로 순위 링크만 받습니다(`KYOBO_READER_*`). 수집에 실패한 날은 "수집 실패"로 기록되고 Telegram 메시지에 상품 페이지 링크가 붙으며, 다음 비교는 직전 정상 수집분과 합니다.
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

### Codex OAuth refresh token이 다른 클라이언트에 의해 소비됨

챗봇이 다음과 같이 실패하는 경우:

```text
Provider authentication failed: Codex refresh token was already consumed by
another client (e.g. Codex CLI or VS Code extension). Run codex in your
terminal to generate fresh tokens, then run hermes auth to re-authenticate.
```

같은 OAuth refresh token을 로컬 Codex CLI/VS Code와 Modal Hermes가 공유하다가 한쪽이 refresh하면서 다른 쪽 토큰이 무효화된 상태입니다. 로컬에서 토큰을 새로 받아 Modal Secret을 갱신하면 복구됩니다.

```bash
# 1) 로컬에서 새 토큰 발급 (둘 중 하나)
hermes auth
# 또는
codex   # 로그인 흐름이 끝나면 ~/.hermes/auth.json이 갱신됨

# 2) auth.json mtime이 갱신됐는지 확인
ls -la ~/.hermes/auth.json

# 3) Modal Secret 재생성 — auth.json 전체를 다시 base64로 올림
python3 scripts/create_modal_secret.py

# 4) 새 secret을 컨테이너가 읽도록 재배포
modal deploy modal_app.py
```

참고: `create_modal_secret.py`는 `modal secret create --force`로 `hermes-modal-secrets`를 통째로 교체합니다. 다만 `GITHUB_TOKEN`은 별도 Modal Secret(`github-secret`)에서 관리하므로 이 스크립트로 인해 덮어써질 일은 없습니다. UI에서 모든 secret을 갱신해서 쓰는 흐름이라면 아래처럼 base64만 직접 갱신해도 됩니다.

```bash
# Modal UI에서 수동 갱신용 base64 출력
base64 -w0 < ~/.hermes/auth.json
```

근본적으로는 로컬 Codex CLI 사용을 줄이거나, OpenRouter/Anthropic/OpenAI API key provider로 전환하면 이 충돌이 사라집니다.

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
