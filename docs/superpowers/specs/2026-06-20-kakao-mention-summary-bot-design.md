# 카톡 방 멘션 요약봇 설계 (kakao-collector 발신)

- 작성일: 2026-06-20
- 상태: 설계 승인됨
- 대상 저장소: `benelog/hermes-modal`
- 선행 설계: `2026-06-20-kakao-bookclub-summary-bot-design.md`(수집·Telegram 요약), `2026-06-20-kakao-multiroom-local-store-design.md`(멀티룸/로컬 저장)

## 1. 목표

기존엔 Telegram에서 "카카오톡 ABC방 요약해줘"로 요약을 받았다. 이번에는 **카카오톡 방 안에서**
누군가 **`@정상혁`을 멘션하며 "요약해줘"** 라고 하면, 그 방의 최근 대화를 한국어로 요약해 **그 방으로 답장**한다.

- 요약 생성은 새 API 키 없이 **Modal에 이미 떠 있는 Hermes의 LLM**(Codex/gpt-5.5)을 재사용한다.
- 카톡 발신(쓰기)을 새로 도입한다 — 기존 **읽기 전용 원칙을 깨는 부분**이며, 안전장치를 둔다.

## 2. 핵심 제약과 결정

- **응답이 폰으로 돌아와야 한다**: Telegram은 자체 푸시로 응답하지만, 카톡 방에 답하려면 요약문이
  폰으로 와서 입력창에 타이핑돼야 한다. → 요약 엔드포인트는 **동기 요청/응답**(summary를 그대로 반환).
- **Hermes LLM 재사용 = `hermes -z`**: Hermes CLI의 one-shot 모드(`-z PROMPT`)는 최종 응답 텍스트만
  stdout으로 출력(배너/스피너 없음, 승인 자동 우회). Modal 컨테이너에서 이를 subprocess로 호출해
  요약문을 받는다. 별도 모델 키가 필요 없다.
- **툴 의존 최소화**: 메시지를 프롬프트에 직접 담아 넘긴다(엔드포인트가 `kakao_dict`를 직접 읽음).
  Hermes는 순수 요약기로만 쓰여, one-shot에서 터미널 툴 사용 여부에 의존하지 않아 안정적이다.
- **외부 발신 = 안전장치 필수**: 그룹 방에 실제 메시지를 보내므로(되돌릴 수 없음) 오발신/루프/스팸을 막는다.

## 3. 데이터 흐름

```
[폰: 접근성] 방에서 "@정상혁 … 요약" 새 메시지(맨 아래=최신) 감지
   │  HTTPS POST {room, command}   (?token=...)
   ▼
[Modal] kakao_summarize (신규 @fastapi_endpoint, POST)
   · 토큰 검증
   · command에서 기간(since) 추출 → kakao_dict에서 해당 room 최근 메시지 조회(select_messages 재사용)
   · 메시지를 프롬프트에 담아 `hermes -z "<요약 지시>"` 실행 → stdout = 요약문
   · {ok, count, since, summary} 반환
   ▲  summary
[폰: 접근성] '발신 직전 현재 방 == 트리거 방' 재확인 후
   입력창 EditText에 ACTION_SET_TEXT(마커+summary) → 전송버튼 ACTION_CLICK
```

## 4. 컴포넌트

### 4.1 Modal `kakao_summarize` (신규, `modal_app.py`)

- 데코레이터: `@app.function(image, secrets=[secret], volumes=volume_mounts, timeout=60*10, env=common_env)`
  + `@modal.fastapi_endpoint(method="POST", label="kakao-summarize")`.
  - 기존 `kakao_ingest`/`kakao_messages`(경량, 볼륨 없음)와 달리 **이미지+볼륨+시크릿**이 필요하다
    (auth.json/config로 Hermes 모델 인증). `gateway`/`cron_tick`과 동일한 마운트.
- 입력: query `token`, body `{"room": str, "command": str}`.
- 절차:
  1. `token != KAKAO_COLLECTOR_TOKEN` → 401.
  2. `since = extract_since(command)`(아래 순수함수).
  3. `select_messages(records, room or None, since, now)`로 `kakao_dict`에서 조회(만료키 정리는 기존과 동일 best-effort).
  4. `prepare_runtime.py --fast` 실행(config/auth/skills 보장 — `doctor`/`cron_tick`과 동일).
  5. 메시지를 `"[client_time] sender: text"` 줄로 만들어 프롬프트에 포함. 빈 기간이면 "수집된 메시지 없음" 안내문을 summary로.
  6. `hermes -z "<프롬프트>"` subprocess(capture stdout, env=common_env+os.environ, timeout ~8분). 비정상/빈 출력이면 `{ok:false, error}`.
  7. `{ok, count, since, summary}` 반환(summary는 strip).
- 프롬프트 요지(카톡 방에 그대로 전송됨): 한국어, **마크다운 헤더 없이 간결히**, 그룹 채팅 1개 메시지에
  적당한 길이, 핵심 화제·결정/약속 위주, 메시지에 없는 내용 지어내지 않기, 유실 가능성 전제.

### 4.2 순수 로직 `scripts/kakao/collector_core.py`

- `extract_since(command: str) -> str`: 자연어 명령에서 기간 토큰을 뽑아 `"Nday"`/`"Nhour"` 형태 문자열로
  반환(없으면 `"1day"`). 규칙: `(\d+)\s*(일|day|d)` → `Nday`, `(\d+)\s*(시간|h|hour)` → `Nhour`,
  "오늘"→`1day`, "어제"→`2day`, "이번주|일주일|한주"→`7day`. 반환값은 기존 `parse_since_to_timedelta`가 그대로 해석.
- `tests/test_kakao_collector_core.py`에 단위테스트 추가.

### 4.3 Android 앱

기존 파일(이름 유지) + 신규 순수/도우미 클래스.

- `SummaryTrigger.kt`(신규, 순수): `isTrigger(text, mentionKeyword, summaryKeyword, botMarker): Boolean`
  - `botMarker`로 시작/포함하면 false(봇 자기 발신 제외).
  - `text`가 `mentionKeyword`와 `summaryKeyword`를 모두 포함하면 true(대소문자/공백 느슨하게).
- `KakaoCollectorService.kt`(변경): `scrape()` 순회에서 **맨 아래(최신) msgId 노드**를 추적.
  그 노드의 값이 새것(firstSeen)이고 `SummaryTrigger.isTrigger`면 트리거:
  - 자동발신 설정이 ON일 때만.
  - 명령 처리 key를 영속화(MessageStore)해 재스크롤/재시작 시 재발화 방지.
  - 방별 진행중 플래그로 동시 1건 제한.
  - 백그라운드로 `Summarizer.request(room, command)` → 결과 도착 시 `sendToRoom(room, summary)`.
- `Summarizer.kt`(신규): `kakao_summarize`로 POST(`{room, command}`, ?token), 긴 readTimeout(~180s),
  성공 시 `{ok, summary}` 반환.
- `Sender.kt`(신규): 접근성으로 발신. 입력창 EditText(id) 찾기 → `ACTION_SET_TEXT`(마커+summary) →
  전송버튼(id) 찾기 → `ACTION_CLICK`. **발신 직전 `currentRoomTitle()==트리거 방` 재확인**(불일치 시 중단/로그).
  서비스가 노드를 다시 가져와 수행(여러 윈도우 훑기, 기존 패턴 재사용).
- `Settings.kt`/`MainActivity.kt`(변경): 신규 설정 키 + 입력 UI.
  - `summarizeUrl`(기본 `https://benelog--kakao-summarize.modal.run`)
  - `mentionKeyword`(기본 = ownName), `summaryKeyword`(기본 `요약`)
  - `inputId`(입력창), `sendId`(전송버튼) — 캘리브레이션으로 확정
  - `botMarker`(기본 `🤖`)
  - `autoReplyEnabled`(기본 **false** — 캘리브레이션 후 사용자가 켬)
  - 수동 버튼 "지금 요약 테스트": firstRoom 요약을 받아 **앱 status에 표시(발신 안 함)** — Hermes 경로를 발신과 분리 검증.

## 5. 안전장치 (외부 발신)

- 자동발신 **기본 OFF**(입력창/전송버튼 id 캘리브레이션 후 수동 ON).
- 루프 방지: 봇 발신은 마커 접두 + 트리거에서 마커 포함 메시지 제외.
- 오발신 방지: 발신 직전 방 일치 재확인, 방별 동시요약 1건, **최신(맨 아래) 새 메시지만** 명령 인정 + 처리키 영속화.
- 누구나 트리거(요청대로). 화이트리스트는 v1 범위 밖(추후 설정 토글).

## 6. 캘리브레이션 (기기 1회, 재빌드 불필요)

기존 CALIBRATE 덤프(`adb logcat -s KakaoCollector`)로 **입력창 EditText id**와 **전송버튼 id**를 확인해
앱 설정에 입력. 멘션 텍스트("@정상혁") 형태는 멘션 키워드 부분일치로 흡수.

## 7. 테스트 계획

1. **순수 로직**: `extract_since` 단위테스트(기존 `python3 tests/test_*.py` 흐름).
2. **엔드포인트 단독**: `kakao_summarize`에 curl로 `{room, command}` POST → 한국어 요약 JSON 확인(토큰 401 포함).
3. **앱 수동**: "지금 요약 테스트" → 앱 status에 요약 표시(발신 경로와 분리 검증).
4. **E2E**: 입력창/전송버튼 캘리브레이션 → 자동발신 ON → 방에서 "@정상혁 요약해줘" → 그 방에 요약 답장.
   기간 변형("3일치"), 스크롤 백필 시 옛 명령 미발화, 봇 메시지 루프 없음 확인.

## 8. 범위 밖 / 향후

- 긴 요약 다중 메시지 분할(현재 1개 메시지·간결 프롬프트).
- 화이트리스트 트리거(설정 토글만 남김).
- 매일 cron 자동요약(별도).
- 전송 실패 재시도/오프라인 큐(기존 TODO와 동일하게 후순위).
