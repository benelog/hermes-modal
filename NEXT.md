# NEXT — 카카오톡 북클럽 요약봇 진행 상황 / 이어가기

마지막 업데이트: 2026-06-20

## 목표
카카오톡 단일 방 **'아카라카북클럽'**의 메시지를 수집해, Telegram에서
"카카오톡 ABC방 요약해줘"라고 보내면 최근(기본 24h) 대화를 한국어로 요약해
Telegram으로 돌려준다. 요약·전달은 Modal에서 실행. 카톡은 **읽기 전용**.

설계: `docs/superpowers/specs/2026-06-20-kakao-bookclub-summary-bot-design.md`
계획: `docs/superpowers/plans/2026-06-20-kakao-bookclub-summary-bot.md`

## 아키텍처 (3분할)
```
[폰 디바이스 앱]  대상 방 화면을 읽어(접근성) 또는 알림으로 캡처
   → {room, sender, text, ts} 를 HTTPS POST
[Modal] kakao_ingest(POST) → modal.Dict "kakao-collect"에 중복제거 적재
        kakao_messages(GET) → since 기간 메시지 JSON 반환 (14일 보존, 읽을 때 정리)
[Hermes(기존 Telegram gateway)] skill 'kakao-room-summary'가
        kakao_fetch.py로 조회 → 한국어 요약 → Telegram 답장
```
- 트리거/전달은 전부 **기존 Telegram gateway**. (Modal→폰 푸시 불가 문제를 이렇게 우회)
- `since`는 **수집 시각(received_at, 서버 스탬프) 기준** (전송시각 아님). "열면 캡처" 모델상
  오늘 처음 스크롤한 오래된 메시지도 "오늘" 요약에 포함될 수 있음 = "지난 N일 수집분".

## ✅ 완료 (서버/Modal 측 — 배포·검증까지 끝)
- 코드 구현 + 단위테스트 (25개, 전부 통과). `main`에 커밋·push 완료.
  - `scripts/kakao/collector_core.py` (+`__init__.py`) — 순수 로직
    (`message_key`, `parse_since_to_timedelta`, `normalize_item`, `select_messages`, `expired_keys`)
  - `modal_app.py` — `kakao_ingest`(POST), `kakao_messages`(GET), `modal.Dict "kakao-collect"`,
    이미지에 `fastapi[standard]`, `common_env`에 `KAKAO_MESSAGES_URL`
  - `scripts/cron/kakao_fetch.py` — Hermes 조회 헬퍼(스킬이 실행, JSON 출력)
  - `scripts/skills/knowledge-base/kakao-room-summary/SKILL.md` — Hermes 스킬
  - `scripts/create_modal_secret.py` — `KAKAO_COLLECTOR_TOKEN` 생성/포함
  - `scripts/prepare_runtime.py` — `write_env_file`에 `KAKAO_COLLECTOR_TOKEN`/`KAKAO_MESSAGES_URL` 추가
    (+ 기존 버그 수정: `write_hermes_config`의 중복 `quick_commands` 키 제거)
  - 테스트: `tests/test_kakao_collector_core.py`, `test_kakao_fetch.py`,
    `test_create_modal_secret.py`, `test_prepare_runtime.py`
- 디바이스 가이드(둘 다 레포에 있음): `scripts/tasker/README.md`(Tasker+AutoInput),
  `scripts/autojs/README.md`(AutoJs6)
- **배포 완료**: `modal deploy modal_app.py` 성공. 엔드포인트 라이브:
  - POST ingest:  `https://benelog--kakao-ingest.modal.run`
  - GET messages: `https://benelog--kakao-messages.modal.run`
  - (label 기반 고정 주소라 재배포해도 동일)
- **시크릿**: `KAKAO_COLLECTOR_TOKEN`을 `~/.hermes/.env`에 영속화 + Modal `hermes-modal-secrets`에 반영.
  ⚠️ **토큰 값은 git/이 파일에 적지 않음.** 값은 `~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN`과
  Modal 시크릿에 있음. (분실 시 새로 만들고 `python3 scripts/create_modal_secret.py` + 폰 값 갱신)
- **라이브 검증 완료**: 잘못된 토큰→401, 적재 ok, **중복제거 동작**, 조회 정상, 저장소 비워둠(count:0).
- **Telegram 발신(outbound) 정상** 확인(가이드/파일 전송 성공). DM chat_id = `7160469912`.

## ⏳ 남은 일 = 디바이스 캡처 앱 셋업 (아직 미완)
대상 방을 긁어 `/ingest`로 POST하는 폰 앱. **여기가 다음 작업.**

> **결정됨: 전용 앱(custom APK)으로 감.** 코드 스캐폴딩 완료 → `android/` (Kotlin,
> AccessibilityService, 단일 목적, 무료, 조용한 방 OK, 내 서명 APK 사이드로딩).
> 빌드/캘리브레이션/설치 가이드: `android/README.md`. (AutoInput 유료·범용엔진 신뢰 이슈를
> 전용앱이 해소 — 내 코드라 신뢰 명확, 결제 불필요.)
> 남은 건: ① Android Studio로 빌드 ② Calibration으로 노드 id 3개 확정 ③ 설치·수집·E2E.

### 디바이스 옵션 & 현재 결정 상태
"앱마켓 정식 + 조용한 방 + 구조적 읽기"를 **동시에 만족하는 무료 길은 없음.** 하나는 양보 필요:

| 방식 | 앱마켓 | 조용한 방 | 비용 | 난이도 | 비고 |
|---|---|---|---|---|---|
| Tasker + AutoInput | ✅ | ✅ | **AutoInput 유료**(약 7일 체험 후) | 중상 | 구조적 읽기 |
| MacroDroid(알림) | ✅ | ❌(알림 켜야) | 사실상 무료 | **낮음** | 설정 시점부터 오는 메시지만, 소급 불가 |
| AutoJs6 | ❌(사이드로딩) | ✅ | 무료 | 중 | 올인원 |

진행 중 발견/이슈:
- Tasker는 설치·설정함. Calibrate Task 만들다가 ① "Code" 액션 위치(=`Code→JavaScriptlet`,
  또는 액션추가 화면 검색창에 `JavaScriptlet`), ② **방을 연 채 실행하는 법**(Task 맨 위 `Wait 5s`
  → ▶ 실행 → 바로 카톡 대상 방으로 전환; Tasker는 백그라운드로 계속 실행)까지 안내함.
- 그러다 **AutoInput이 유료(AutoApps 묶음)** 인 걸 확인. AutoApps는 AutoInput을 포함한 플러그인
  브랜드일 뿐, AutoInput을 대체하는 다른 앱이 아님 — UI Query 기능은 여전히 AutoInput 필요.
- 그래서 **다음 결정 필요**: (A) AutoInput 체험판으로 지금 흐름 마저 검증 → 가치 있으면 결제,
  (B) 조용한 방 포기하고 **MacroDroid 알림 방식**(가장 간단·무료, 단 그 방 알림 켜두기)으로 전환.

### Calibration 개념 (Tasker 경로로 갈 경우)
- 카톡 화면의 메시지본문/보낸이/시각 노드의 **resource-id 3개**(`%IDMSG/%IDNAME/%IDTIME`)를
  한 번 알아내는 작업. 버전마다 달라서 직접 확인 필요.
- 방법: 대상 방 띄운 채 `AutoInput UI Query`(필터 없음) → JavaScriptlet으로 "텍스트 => id"
  덤프 → Set Clipboard. 덤프를 Claude에 붙여넣으면 id 3개 골라줌.
- Tasker 설정 변수: `%ROOM=아카라카북클럽`, `%URL=https://benelog--kakao-ingest.modal.run`,
  `%TOKEN=<~/.hermes/.env의 값>`, `%IDMSG/%IDNAME/%IDTIME=<calibration 결과>`.

## 핵심 레퍼런스 값
- 워크스페이스 `benelog`, Modal app `hermes-telegram-gateway`.
- 디바이스 POST: `POST https://benelog--kakao-ingest.modal.run?token=<TOKEN>`
  body `{"room":"아카라카북클럽","sender":"...","text":"...","ts":"오후 3:25"}`
- 조회: `GET https://benelog--kakao-messages.modal.run?token=<TOKEN>&since=1day[&room=...]`
- 저장소 `modal.Dict "kakao-collect"`, dedupe key = `room|sha1(room\x01sender\x01text\x01client_time)`,
  보존 14일(읽을 때 정리).
- 대상 방: **'아카라카북클럽' 1개만**.
- 토큰 위치: `~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN` + Modal 시크릿 (git에는 없음).
- Telegram DM chat_id: `7160469912`.

## 다음에 할 일 (체크리스트)
- [x] **디바이스 방식 결정**: 전용 앱(`android/`, AccessibilityService).
- [ ] **앱 빌드**: Android Studio로 `android/` 열기(첫 동기화에 Gradle wrapper 생성) → `Config.kt`의
  `TOKEN` 채우기(`~/.hermes/.env`에서) → 빌드. 상세 `android/README.md`.
- [ ] **Calibration**: `Config.CALIBRATE=true` 빌드·설치 → 접근성 ON → 대상 방 열기 →
  `adb logcat -s KakaoCollector`로 `MSG_ID/NAME_ID/TIME_ID/TITLE_ID` 확정 → `CALIBRATE=false` 재빌드.
- [ ] 앱으로 캡처 1회(방 열고 스크롤) → `curl ".../kakao-messages?token=<TOKEN>&since=1day"`로 적재 확인.
- [ ] **Telegram 수신(webhook) 확인**: 시크릿 재생성 때 `TELEGRAM_WEBHOOK_SECRET`이 새로 생성됨.
  프로젝트가 기동 시 webhook 재등록하도록 설계됐지만, **봇에 아무 메시지나 보내 정상 응답하는지 확인**.
  안 되면 webhook 재설정 필요.
- [ ] E2E: 봇에게 "카카오톡 ABC방 요약해줘" → 한국어 요약 답장 확인. "3일치" 등 기간 변형도.
- [ ] (선택, 나중) 매일 07:00 cron 자동요약 추가 — `cron_tick`이 이미 07:00/19:00 KST에 깨움.
  Hermes cron job + `cron_jobs/*.json` 버전관리. 온디맨드 충분히 검증 후.

## 명령어 메모
- 테스트: `python3 tests/test_kakao_collector_core.py -v` (pytest 미설치, 각 파일 직접 실행)
- 배포: `modal deploy modal_app.py`
- 시크릿 재생성: `python3 scripts/create_modal_secret.py` (값은 `~/.hermes/.env`에서 읽음)
- 저장소 비우기: `modal dict clear kakao-collect -y`
- 적재/조회 확인: 위 ingest/messages curl 사용
