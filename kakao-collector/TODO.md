# TODO — Kakao Collector 앱

다음 세션은 이 앱을 다듬는다. 전체 맥락·구조·원리는 같은 폴더 `README.md` 참고.
현재: 앱 스캐폴딩만 완료(빌드·실기기 검증 전). 서버측(Modal/Hermes)은 배포·검증 완료.

## A. 먼저 — 동작시키기 (필수 경로)
- [x] **첫 빌드 완료**: Android SDK(cmdline-tools + platform-34 + build-tools 34.0.0)를 `~/Android/Sdk`에
      설치, JDK 17(`~/.sdkman/.../17.0.16-tem`) + gradle wrapper 8.7로 `./gradlew assembleDebug` 성공.
      산출물 `app/build/outputs/apk/debug/app-debug.apk`. (빌드 방법은 README §6 참고.)
- [x] **폰 설치 완료**: Pixel 10 Pro XL(Android 16, API 36)에 `./install.sh`로 설치 확인.
      (Linux adb 권한은 `./setup_udev.sh`로 해결. 빌드는 JDK 17 필요 — 전역 JDK 25면 `What went wrong: 25` 실패.)
- [x] **토큰은 이제 앱에서 입력**: `Config.kt` 수정 불필요. 앱 실행 → "설정"에서 토큰 입력 후 "설정 저장".
      (`~/.hermes/.env`의 `KAKAO_COLLECTOR_TOKEN`과 동일 값.)
- [x] **Calibration 완료**: 실측 id 확정 — 본문 `id/message`, 보낸이 `id/nickname`, 시각 `id/time`(희소),
      방제목 `id/name`. `Config.kt` 기본값에 반영. (접근성 켜기는 `enable_service.sh`로 — 제한된 설정 우회.)
- [x] **수집 검증 완료**: 방 열고 스크롤 → 실제 메시지(보낸이/본문 정확)가 Modal `/messages`에 적재 확인.
      검증 중 발견·수정한 버그: ①방 매칭 래치화(스크롤 중 끊김), ②일시 윈도우에서 래치 오프 방지,
      ③보낸이 미상(빈 sender) 메시지 스킵(중복 적재 방지).
- [x] **보낸이 귀속 완성**: 내 메시지엔 닉네임이 안 떠서 직전 남 닉네임으로 오귀속되던 문제 →
      **정렬(우측=내것/좌측=남것)** 으로 판별, 내 메시지는 설정의 "내 닉네임"(예: 정상혁)으로 고정.
      남 메시지 수집·미리보기 제외도 함께 검증. (E2E: 내것→정상혁, 김응수 PDF→김응수 확인.)
- [ ] (정리) Modal 저장소에 수정 전 테스트 잔여(빈-sender 중복 3건) 있음 — 실사용 전
      `modal dict clear kakao-collect -y`로 비우면 깨끗(14일 후 자동 만료되기도 함).

## B. 앱 다듬기 (이번 세션 핵심)
- [x] **토큰을 코드 밖으로**: 완료. `Settings.kt`(SharedPreferences) + 설정 화면에서 토큰/URL/방이름/ids/CALIBRATE
      편집. `Config.kt`는 이제 기본값(폴백)만 보유 → 실값을 git에 두지 않음. `Poster`/`Service`는 `Settings`를 읽음.
- [x] **중복 상태 영속화**: 로컬 SQLite(`collector.db`)로 영속화 완료. 서비스 시작 시 DB에서
      최근 키를 시딩해 재시작 후 재전송을 방지. `sent_ok`로 전송 성공여부도 기록.
- [x] **멀티룸(대상 방 목록) 지원**: 설정의 "대상 방 목록"에서 방 제목을 여러 줄로 입력 가능.
      서비스가 열린 방을 목록과 매칭해 `room` 태그를 붙여 전송. 방 식별은 표시명 기준(접근성에는
      고유 ID 없음). Modal 서버는 이미 멀티룸이라 변경 없음.
- [ ] **전송 실패 처리**: 네트워크 오류 시 재시도/오프라인 큐(간단한 보관 후 재전송).
- [ ] **상태 UI**: 마지막 전송 시각/누적 건수/최근 오류 표시(현재는 status 텍스트만).
- [ ] **보낸이 귀속 엣지케이스**: 연속 메시지에서 보낸이 한 번만 보일 때 승계 로직 점검,
      동명이인/시스템 메시지("들어왔습니다" 등) 필터.
- [ ] **사진/스티커/이모티콘**: `[사진]` 등 비텍스트 항목을 건너뛸지/표기할지 정리.
- [ ] **(알려진 한계) AccessibilityNodeInfo recycle 미적용**: `walk()`가 모은 노드를 `scrape()`가
      나중에 읽는 지연순회 구조라 walk 중 recycle하면 use-after-recycle. API 33+에선 recycle이 no-op이고
      현 기기(API 36)는 무관하지만, API 26-32 장시간 사용 시 노드 풀 압박 가능 → 필요해지면 scrape 처리 후
      일괄 recycle하도록 구조 변경.

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
