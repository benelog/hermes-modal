# 카카오톡 북클럽 대화 요약봇 설계

- 작성일: 2026-06-20
- 상태: 설계 승인됨 (구현계획 작성 전)
- 대상 저장소: `benelog/hermes-modal` (Modal 기반 Hermes 배포)

## 1. 목표

카카오톡의 특정 단일 대화방 **'아카라카북클럽'**에 쌓인 메시지를 수집해, Telegram에서
**"카카오톡 ABC방 요약해줘"** 같은 자연어 명령을 보내면 그날치(기본 최근 24시간) 대화를
한국어로 요약해 Telegram으로 돌려받는다.

- 다른 프로젝트 기능들처럼 요약·전달은 **Modal**에서 실행한다.
- 1차 범위는 **온디맨드(Telegram 트리거)**까지. 매일 07:00 cron 자동 요약은 충분히
  테스트한 뒤 나중에 추가한다.

## 2. 핵심 제약과 리서치 근거

이 설계의 모양은 카카오 연동의 현실에서 강제된다. 조사 결론:

1. **친구/그룹 대화방 메시지를 서버가 읽는 공식 카카오 API는 없다.**
   - 메시지 API: 보내기 전용("나에게"/동의한 친구). 읽기 엔드포인트 없음.
   - 카카오 채널 + i 오픈빌더 챗봇: 채널과 1:1로 들어온 발화만 skill 서버로 받음.
     일반 그룹 대화방의 전체 스트림은 못 봄. 기존 친구방에 주입 불가.
   - 카카오워크 봇 API: 보내기 전용, 히스토리 읽기 없음.
   - 대화 내보내기(.txt): 존재하지만 수동 버튼이라 서버 자동화 불가.
2. **따라서 캡처는 비공식이며, 메시지 도착 시점에 온라인인 "사용자 쪽 기기"가 필요하다.**
   - Modal은 캡처 지점이 될 수 없다: scale-to-zero라 카톡 로그인 클라이언트를 상시
     유지할 수 없고, 컨테이너마다 로그인 시도 시 기기 인증/단일기기 로그인/LOCO 비정상
     클라이언트 탐지로 가장 빨리 차단되는 패턴이다.
3. **`remote-kakao/core`는 채택하지 않는다.**
   - 결국 MessengerBot R(안드로이드 알림 봇) 위에 얹은 Node UDP 서버 SDK다. 상시 안드로이드
     기기 요구를 없애주지 못하고, UDP/상시 서버 모델이 Modal의 HTTPS·scale-to-zero와 안 맞는다.
     `next` 브랜치(WebSocket/SSE)는 미완성. → 디바이스는 직접 캡처해 **stateless HTTPS POST**가 정석.
4. **조용한 방(알림 끔) 요구 때문에 캡처 방식은 접근성 스크래핑으로 한다.**
   - 알림 리스너(MessengerBot R)는 muted 방에서 알림이 안 떠 아무것도 못 잡는다.
   - "방을 열어 확인하는 순간 전송"은 화면을 읽는 것 = 안드로이드 **AccessibilityService**.
     **AutoJs6**(오픈소스, 접근성 + `http.postJson` 내장)로 구현한다.

ToS: 모든 캡처는 비공식이지만, 본 설계는 **읽기 전용·단일 방·저용량·본인이 멤버인 방**으로
가장 낮은 리스크 구성이다. 카톡 쪽은 절대 발신하지 않는다(로컬 화면만 읽음 → 서버측 자동화
신호 없음). 트리거·요약·전달은 모두 Telegram에서 이뤄진다.

## 3. 전체 구조

```
[내 안드로이드 폰]  AutoJs6 접근성 스크립트
   · '아카라카북클럽' 방이 열린 것을 감지
   · 화면의 메시지 노드(보낸이·본문)를 읽고 위로 자동 스크롤하며 기간/기수신분까지 수집
   · 각 메시지 → Modal /ingest 로 HTTPS POST (공유 토큰 인증). 카톡엔 발신하지 않음(읽기 전용)
        │
        ▼  POST {room, sender, text, ts}
[Modal] kakao_collector (신규 @fastapi_endpoint)
   · POST /ingest    : 토큰 검증 → modal.Dict 적재(메시지별 고유 키로 중복 제거)
   · GET  /messages  : 토큰 검증 → since 기간의 메시지를 시간순 JSON으로 반환
   · 저장소: modal.Dict (컨테이너 간 즉시 공유, Volume staleness 회피). 14일 경과분 정리
        ▲  curl GET /messages?since=1day
[Modal] 기존 Telegram gateway = Hermes (변경 없음, skill만 추가)
   · Telegram에 "카카오톡 ABC방 요약해줘" 입력
   · 신규 skill 'kakao-room-summary' 발동 → /messages 호출 → LLM 요약 → Telegram 답장
```

데이터 흐름의 핵심: **수집은 상시(방을 열 때마다), 요약은 트리거 시점**. `/summary`류 요청은
이미 적재된 메시지를 요약하는 것이지 과거를 끌어오는 게 아니다(접근성 스크래핑도 화면에 있는
것만 읽으므로, 사용자가 방을 열어 스크롤해 둔 범위가 적재 대상이다).

## 4. 컴포넌트

### 4.1 디바이스 브리지 — AutoJs6 접근성 스크립트 (신규, 폰에 설치)

- 카톡에서 대상 방이 표시 중인지 접근성 윈도우의 패키지(`com.kakao.talk`) + 방 제목 노드로 판별.
- 방이 열리면 메시지 영역(RecyclerView)의 노드에서 `{sender, text}`를 읽고, 위로 자동 스크롤하며
  목표 기간 또는 이미 수집한 메시지에 도달할 때까지 수집한다.
- 각 메시지를 `http.postJson(INGEST_URL, {room, sender, text, ts})` 로 전송. 인증 토큰 헤더 포함.
- 카톡에는 **아무것도 보내지 않는다**(읽기 전용).
- 설정 상수: `ROOM_NAME`(실제 표시명), `INGEST_URL`, `COLLECTOR_TOKEN`.

확인 필요(구현 시):
- '아카라카북클럽' 방의 **실제 화면 제목 문자열**(폰에서 1회 확인).
- 카톡 메시지 말풍선의 접근성 노드 구조(resource-id/클래스) — 보낸이/본문/시각 귀속 방법.
  연속 메시지는 보낸이가 한 번만 표시될 수 있어 직전 보낸이를 이어붙이는 처리가 필요.

### 4.2 Modal `kakao_collector` (신규, `modal_app.py`에 함수 추가)

기존 `gateway`는 건드리지 않는다. 독립 `@modal.fastapi_endpoint` 함수.

- `POST /ingest`
  - 헤더 토큰 검증(불일치 시 401).
  - 메시지 고유 키 = `f"{room}|{date}|{sha1(sender+text+ts)}"` → modal.Dict에 저장(중복 자동 무시).
  - 값: `{room, sender, text, ts}` (ts는 ISO8601 KST).
- `GET /messages?room=<>&since=<예: 1day|3day>`
  - 토큰 검증.
  - since 기간 내 메시지를 ts 오름차순 JSON 배열로 반환. room 미지정 시 단일 수집 방을 기본.
- 저장소: `modal.Dict.from_name("kakao-collect", create_if_missing=True)`.
  - **modal.Dict 선택 이유**: collector(적재)와 조회가 서로 다른 컨테이너에서 뜨는데, Volume은
    commit/reload 타이밍 때문에 갓 적재한 데이터가 조회 컨테이너에 안 보일 수 있다. Dict는 항상
    최신을 즉시 공유한다. 메시지별 고유 키라 동시 적재에도 read-modify-write 경합이 없다.
  - 보존: 적재 시 또는 조회 시 14일 경과 키를 정리(날짜 프리픽스 기준).
- 시크릿: 신규 Modal Secret 키 `KAKAO_COLLECTOR_TOKEN`(`hermes-modal-secrets`에 추가, 디바이스
  스크립트와 공유). 엔드포인트 URL은 `modal deploy` 후 확보해 디바이스 스크립트에 반영.

### 4.3 Hermes skill `kakao-room-summary` (신규)

- 위치: `scripts/skills/.../kakao-room-summary/SKILL.md`. `prepare_runtime.py`가 부팅 시 `~/.hermes`로
  동기화(기존 skill들과 동일 경로/방식).
- 발동: Telegram에서 "카톡 …요약" 류 요청. 수집 방이 하나뿐이라 방 이름 파싱은 느슨하게(어떤
  표현이든 그 방으로 매핑).
- 동작: `curl GET /messages?since=<기간>`(터미널 toolset) → 받은 메시지를 한국어로 요약 →
  Telegram으로 답장. 기간 미지정 시 기본 **최근 24시간**. "3일치" 등 표현 시 그만큼.
- collector 토큰은 런타임 환경에서 주입(Modal Secret을 통해 컨테이너 env로). 엔드포인트 URL은
  skill/스크립트에 설정.

### 4.4 (나중에) 07:00 cron — 본 범위 밖

- 같은 요약을 도는 Hermes cron job + `modal_app.py::cron_tick`(이미 07:00 KST에 깨움) 활용.
- 1차에서는 만들지 않는다. 온디맨드를 충분히 테스트한 뒤 추가하며, 추가 시 `cron_jobs/*.json`으로
  버전관리(기존 컨벤션).

## 5. 데이터 모델

수집 메시지(JSON):
```json
{ "room": "아카라카북클럽", "sender": "홍길동", "text": "메시지 본문", "ts": "2026-06-20T08:15:03+09:00" }
```
- modal.Dict 키: `"<room>|<YYYY-MM-DD>|<sha1(sender|text|ts)>"` → 위 객체.
- 보존: 14일.

## 6. 엣지 케이스 / 한계

- **메시지 유실은 본질적**: 접근성 스크래핑은 사용자가 방을 열어 스크롤한 범위만 잡는다("열면
  캡처"). 안 열면 안 잡힘 → 베스트-에포트 요약임을 전제(cron 전 테스트로 감각 확인).
- **중복**: 스크롤하며 같은 메시지를 여러 번 읽음 → collector의 고유 키 중복 제거로 흡수.
- **사진/스티커/이모티콘**: 화면 텍스트로 `[사진]` 등으로 잡혀 텍스트 위주 요약이 됨.
- **보낸이/시각 귀속**: 연속 메시지는 보낸이가 한 번만 표시될 수 있음 → 직전 보낸이 승계 처리.
- **카톡 UI 업데이트 시 깨짐**: 접근성 노드 구조가 바뀌면 스크립트 보정 필요(알림 방식보다 잦음).
- **인증**: `/ingest`·`/messages`는 공유 토큰으로 보호. 토큰 없는 요청은 401.
- **빈 기간**: 해당 기간 메시지가 없으면 skill이 "해당 기간 수집된 메시지가 없습니다"로 응답.
- **프라이버시**: 본인이 멤버인 단일 방을 본인용으로 요약하는 개인 용도라는 전제.

## 7. 테스트 계획 (cron 없이)

1. **엔드포인트 단독**: `/ingest`에 curl로 샘플 메시지 POST → `/messages?since=1day`로 되받기 확인.
   토큰 불일치 401, 중복 키 무시, 14일 정리 동작 확인.
2. **디바이스 캡처**: AutoJs6 스크립트로 실제 '아카라카북클럽' 방을 열어 스크롤 → Modal 적재 확인.
   보낸이/본문/시각 귀속과 중복 제거가 맞는지 점검.
3. **E2E**: Telegram에 "카카오톡 ABC방 요약해줘" → Hermes가 `/messages` 호출 → 한국어 요약
   답장까지 확인. 기간 변형("3일치")도 확인.

## 8. 범위 밖 / 향후

- 07:00 cron 자동 요약(테스트 후 추가).
- MessengerBot R 알림 방식 병행(실시간 보강) — 동일 `/ingest`로 합류 가능.
- 요약을 카톡 방에 되쓰기(발신 → 리스크 한 단계↑) — 현재는 Telegram 전달만.
- 미디어(사진 등) 본문 처리, 다중 방 수집.

## 9. 구현 시 확정할 항목

- '아카라카북클럽' 방의 실제 화면 표시명 문자열.
- 카톡 메시지 말풍선 접근성 노드 구조(보낸이/본문/시각 추출 방법).
- collector 엔드포인트 URL(첫 `modal deploy` 후 확보) 및 디바이스 스크립트 반영.
- Hermes gateway 에이전트의 터미널/웹 toolset 사용 가능 여부(skill에서 curl 호출).
