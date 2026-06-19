# TODO — Kakao Collector 앱

다음 세션은 이 앱을 다듬는다. 전체 맥락·구조·원리는 같은 폴더 `README.md` 참고.
현재: 앱 스캐폴딩만 완료(빌드·실기기 검증 전). 서버측(Modal/Hermes)은 배포·검증 완료.

## A. 먼저 — 동작시키기 (필수 경로)
- [ ] **첫 빌드**: Android Studio로 `kakao-collector/` 열기 → Gradle 동기화(wrapper 자동 생성)
      → 컴파일 통과 확인. (이 환경에선 Android SDK가 없어 빌드 검증을 못 했으니, 첫 빌드에서
      나오는 컴파일 에러/경고가 있으면 거기서부터 수정.)
- [ ] `Config.kt`에 `TOKEN` 채우기(`~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN`).
- [ ] **Calibration**: `CALIBRATE=true` 빌드·설치 → 접근성 ON → 대상 방 열기 →
      `adb logcat -s KakaoCollector` 덤프로 `MSG_ID/NAME_ID/TIME_ID/TITLE_ID` 확정 → `CALIBRATE=false` 재빌드.
- [ ] 캡처 1회(방 열고 스크롤) → `curl ".../kakao-messages?token=<TOKEN>&since=1day"`로 적재/필드 확인.

## B. 앱 다듬기 (이번 세션 핵심)
- [ ] **토큰을 코드 밖으로**: `Config.kt` 상수 대신 설정 화면 + `SharedPreferences`(또는
      `EncryptedSharedPreferences`)로 토큰/방이름/ids 편집. 실값을 git에 안 두게 함.
- [ ] **중복 상태 영속화**: 현재 `seen`은 인메모리(앱 재시작 시 초기화) → 재시작해도 유지되게
      (서버 중복제거가 있으니 치명적이진 않지만 트래픽 절약).
- [ ] **전송 실패 처리**: 네트워크 오류 시 재시도/오프라인 큐(간단한 보관 후 재전송).
- [ ] **상태 UI**: 마지막 전송 시각/누적 건수/최근 오류 표시(현재는 status 텍스트만).
- [ ] **보낸이 귀속 엣지케이스**: 연속 메시지에서 보낸이 한 번만 보일 때 승계 로직 점검,
      동명이인/시스템 메시지("들어왔습니다" 등) 필터.
- [ ] **사진/스티커/이모티콘**: `[사진]` 등 비텍스트 항목을 건너뛸지/표기할지 정리.

## C. 선택 — 자동화/편의 (가치 보고 결정)
- [ ] **자동 스크롤 백필**: `performAction(ACTION_SCROLL_BACKWARD)`로 과거분 일괄 수집
      (목표 기간 또는 기수집분 도달까지). 사용자 조작과 충돌 안 나게 트리거 분리.
- [ ] **반자동 트리거**: 특정 시각 또는 "그 방 열림"에 수집 시작(전경 서비스/알람).
- [ ] **전경 서비스/안정성**: Doze·태스크킬 대비(필요할 때만).

## D. 그 다음 — 전체 연결 마무리 (앱 외 잔여)
- [ ] **Telegram 수신(webhook) 확인**: 시크릿 재생성 때 `TELEGRAM_WEBHOOK_SECRET`이 새로 생성됨.
      기동 시 재등록되도록 설계됐지만, 봇에 아무 메시지나 보내 **정상 응답하는지 확인**. 안 되면 webhook 재설정.
- [ ] **E2E**: 봇에게 "카카오톡 ABC방 요약해줘" → 한국어 요약 답장 확인("3일치" 등 기간 변형도).
- [ ] **(나중) 매일 07:00 cron 자동요약**: `cron_tick`이 이미 07:00/19:00 KST에 깨움.
      Hermes cron job + `cron_jobs/*.json` 버전관리. 온디맨드 충분히 검증 후 추가.

## 참고
- 빌드/캘리브레이션 상세, 레퍼런스 값(엔드포인트/토큰위치/dict), 원리는 `README.md`에 있음.
- Telegram DM chat_id `7160469912` (가이드 전송 등에 사용).
