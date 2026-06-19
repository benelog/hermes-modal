# Kakao Collector

카카오톡 특정 단일 방의 메시지를 모아 매일/온디맨드로 **요약**해 받기 위한 시스템에서,
**폰 쪽 수집기(Android 전용 앱)** 부분이다. 이 디렉토리는 그 앱이지만, README는 앱이 속한
**전체 통신 구조·요구사항·추구 원리**까지 함께 기록한다(다음 세션의 단일 진입점).

- 대상 방: **'아카라카북클럽'** (단일 방만 수집)
- 앱 역할: 대상 방 화면을 **접근성으로 읽어** `{room, sender, text, ts}`를 Modal `/ingest`로 POST. **읽기 전용**.
- 요약/전달: 서버(Modal + Hermes)가 담당 — 이 앱과 분리됨.

---

## 1. 목표 / 요구사항

- 카카오톡 '아카라카북클럽' 방의 하루치 메시지를 모아, **Telegram**에서
  "카카오톡 ABC방 요약해줘"라고 보내면 최근(기본 24h) 대화를 **한국어로 요약**해 Telegram으로 받는다.
- 요약·전달은 다른 기능들처럼 **Modal**에서 실행한다.
- **카카오톡은 읽기 전용**으로만 다룬다(발신 안 함 = 리스크 최소).
- **조용한 방(알림 끔)도 수집**되어야 한다 → 알림이 아니라 **화면을 직접 읽는 접근성** 방식이 필수.
- 1차 범위는 **온디맨드(Telegram 트리거)**. 매일 07:00 cron 자동요약은 충분히 테스트 후 추가(범위 밖).

## 2. 전체 통신 구조

```
[폰: Kakao Collector 앱]  대상 방이 화면에 있을 때(사용자가 열어 스크롤) 접근성으로 말풍선 읽기
   │  {room, sender, text, ts}
   ▼  HTTPS POST  /ingest?token=...
[Modal] kakao_ingest(POST)  → modal.Dict "kakao-collect"에 중복제거 적재
        kakao_messages(GET) → since 기간 메시지 JSON 반환 (14일 보존, 읽을 때 정리)
   ▲  curl GET /messages?token=...&since=1day
[Modal: 기존 Telegram gateway = Hermes]
        Telegram "카카오톡 ABC방 요약해줘" → skill 'kakao-room-summary'가
        kakao_fetch.py로 /messages 조회 → 한국어 요약 → Telegram 답장
```

- 트리거·전달은 전부 **기존 Telegram gateway**로 처리(“Modal→폰 푸시 불가” 문제를 이렇게 우회).
- 그래서 폰 앱은 **수집(업로드)만** 하면 되고, 요약 요청/응답은 신경 쓸 필요 없음.

## 3. 추구하는 원리 / 설계 결정

- **공식 API 부재가 모든 것을 결정**: 카카오톡은 친구/그룹 방 메시지를 서버가 읽는 공식 API가 없다.
  따라서 캡처는 비공식이며, **메시지 도착 시점에 온라인인 사용자 기기**가 필요하다.
  Modal은 scale-to-zero라 캡처 지점이 될 수 없어 **폰 ↔ Modal 분리**가 강제됨.
- **조용한 방 = 접근성만 가능**: 알림 기반(MacroDroid 알림/IFTTT/Tasker AutoNotification 등)은
  muted 방에서 알림이 안 떠 못 잡는다. 화면을 읽는 **AccessibilityService**만이 조용한 방을 본다.
- **전용 앱을 택한 이유**: "앱마켓 정식 + 조용한 방 + 무료"를 동시에 만족하는 길은 없었다
  (Tasker+AutoInput=AutoInput 유료, MacroDroid=알림이라 muted 불가, AutoJs6=사이드로딩+범용엔진 신뢰 이슈).
  **내가 짠 단일 목적 앱**은 범용 스크립트 엔진이 아니라 신뢰가 명확하고, 무료이며, 접근성으로 조용한 방도 된다.
  (개인용이라 Play 스토어 미등록 + 내 서명 APK 사이드로딩으로 충분)
- **읽기 전용 원칙**: 접근성 서비스를 `com.kakao.talk`에만 바인딩하고, 카톡에 발신하지 않는다.
  LOCO 같은 비정상 클라이언트 신호가 없어 ToS 리스크가 가장 낮은 구성.
- **“열면 캡처” 모델**: 자동 스크롤 없이, 사용자가 방을 열어 스크롤하는 동안 보이는 것만 수집(best-effort).
  안 열면 수집 안 됨. 단순하고 안전(자동 조작 최소).
- **`since`는 수집 시각(received_at) 기준**: 디바이스가 절대 시각을 신뢰성 있게 못 만들기 때문.
  → 오늘 처음 스크롤한 오래된 메시지도 "오늘" 요약에 포함될 수 있음(= "지난 N일 **수집분**").
- **중복 제거는 양쪽**: 앱(스크롤 중 재노출)과 서버(같은 sender+text+time → 1건) 모두에서 거른다.

## 4. 핵심 레퍼런스 값

- 워크스페이스 `benelog`, Modal app `hermes-telegram-gateway`.
- 디바이스 POST: `POST https://benelog--kakao-ingest.modal.run?token=<TOKEN>`
  body `{"room":"아카라카북클럽","sender":"...","text":"...","ts":"오후 3:25"}`
- 조회: `GET https://benelog--kakao-messages.modal.run?token=<TOKEN>&since=1day[&room=...]`
- 저장소 `modal.Dict "kakao-collect"`, dedupe key = `room|sha1(room\x01sender\x01text\x01client_time)`, 보존 14일.
- 대상 방: **'아카라카북클럽' 1개만**.
- 토큰 위치: 로컬 `~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN` + Modal 시크릿 `hermes-modal-secrets` (**git에는 없음**).
- Telegram DM chat_id: `7160469912`.

## 5. 현재 상태 (2026-06-20)

- **서버측 완료**: 코드+단위테스트(25개 통과), `modal deploy` 완료, 엔드포인트 라이브,
  토큰 시크릿 반영, 라이브검증(401/적재/중복제거/조회) 완료, 저장소 비워둠.
  관련 코드: `scripts/kakao/collector_core.py`, `modal_app.py`(kakao_ingest/kakao_messages),
  `scripts/cron/kakao_fetch.py`, `scripts/skills/knowledge-base/kakao-room-summary/SKILL.md`.
  설계/계획: `docs/superpowers/specs|plans/2026-06-20-kakao-bookclub-summary-bot-*.md`.
- **이 앱(폰 수집기)**: 스캐폴딩 완료, **아직 빌드·캘리브레이션·실기기 검증 전.** ← 다음 세션 작업(아래 TODO).
- 참고로 남겨둔 다른 디바이스 방식 가이드: `scripts/tasker/README.md`(Tasker+AutoInput),
  `scripts/autojs/README.md`(AutoJs6) — 채택은 이 전용 앱.

---

## 6. 앱 빌드 / Calibration / 설치 / 수집

요구사항: Android Studio(JDK 17), Android 8.0(API 26)+ 기기.

### 0) Gradle wrapper (최초 1회)
`gradle-wrapper.jar`(바이너리)는 미커밋. **Android Studio로 `kakao-collector/` 열면 첫 동기화 시 자동 생성**,
또는 `cd kakao-collector && gradle wrapper --gradle-version 8.7`.

### 1) 설정 (`app/src/main/java/net/benelog/kakaocollector/Config.kt`)
- `TOKEN` = `~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN`과 동일 값. ⚠️ **실값을 git에 커밋 금지**(공유 시 placeholder 복귀).
- `ROOM_NAME`/`INGEST_URL`은 이미 채워짐.
- `MSG_ID/NAME_ID/TIME_ID/TITLE_ID`는 아래 Calibration으로 확정.

### 2) Calibration (resource-id 확정 — 최초 1회 필수)
1. `Config.CALIBRATE = true` 로 빌드·설치.
2. 설정 → 접근성 → **Kakao Collector** 켜기(앱의 "접근성 설정 열기" 버튼).
3. 카톡에서 **대상 방을 연다**.
4. `adb logcat -s KakaoCollector` → `id=... text=...` 줄 확인.
5. 화면과 대조해 본문/보낸이/시각/방제목 id를 `MSG_ID/NAME_ID/TIME_ID/TITLE_ID`에 채움.
6. `Config.CALIBRATE = false` 로 되돌리고 재빌드·설치.

### 3) 빌드 / 설치
```bash
cd kakao-collector
./gradlew installDebug     # adb로 디버그 APK 설치
# 또는 APK만: ./gradlew assembleDebug  → app/build/outputs/apk/debug/app-debug.apk
```
(Android Studio ▶ Run도 동일. 디버그 서명이면 개인 사이드로딩 충분)

### 4) 수집 / 확인
1. 접근성 ON 상태에서 **대상 방을 열고 위로 스크롤**.
2. 보이는 메시지가 자동으로 `/ingest`로 전송됨.
3. 확인: `curl -s "https://benelog--kakao-messages.modal.run?token=<토큰>&since=1day"`.
4. 연결만 빠르게 보려면 앱의 **"테스트 메시지 전송"** → `/messages` 확인 → 정리 `modal dict clear kakao-collect -y`.
5. E2E: Telegram 봇에게 "카카오톡 ABC방 요약해줘".

## 7. 동작 / 한계

- 읽기 전용(서비스가 `com.kakao.talk`로만 제한).
- "열면 캡처": 방 열어 스크롤한 범위만 수집(v1은 자동 스크롤 없음).
- 사진/스티커는 `[사진]` 등 텍스트로만.
- 연속 메시지는 직전 보낸이 승계.
- 카톡 UI 업데이트로 id가 바뀌면 Calibration 재실행.
- 중복은 클라이언트+서버 양쪽 제거.

## 8. 구성 파일
```
kakao-collector/
  settings.gradle.kts · build.gradle.kts · gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/
    build.gradle.kts · proguard-rules.pro
    src/main/AndroidManifest.xml
    src/main/java/net/benelog/kakaocollector/
      Config.kt                 설정 상수(토큰/방/ids/CALIBRATE)
      KakaoCollectorService.kt  접근성 수집 서비스(핵심)
      Poster.kt                 /ingest POST
      MainActivity.kt           상태/접근성설정/테스트 화면
    src/main/res/...            레이아웃·문자열·테마·접근성설정·아이콘
  TODO.md                       다음 세션 할 일
```

명령어 메모(서버측, 참고): 테스트 `python3 tests/test_*.py`, 배포 `modal deploy modal_app.py`,
시크릿 재생성 `python3 scripts/create_modal_secret.py`, 저장소 비우기 `modal dict clear kakao-collect -y`.
