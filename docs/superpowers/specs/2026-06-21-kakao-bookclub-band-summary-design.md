# 카카오톡 북클럽 → BAND 주간 요약 cron 설계

- 작성일: 2026-06-21
- 상태: 설계 승인됨 (구현계획 작성 전)
- 대상 저장소: `benelog/hermes-modal` (Modal 기반 Hermes 배포)
- 관련 설계(형제): `docs/superpowers/specs/2026-06-20-kakao-bookclub-summary-bot-design.md`
  (온디맨드 텔레그램 요약). 그 설계의 §4.4에서 "07:00 cron 자동 요약"은 범위 밖으로
  미뤄졌고, 본 문서가 그 조각을 **BAND 출력 + 증분 범위 + 풍부한 추출**로 확장해 정의한다.

## 1. 목표

카카오톡 단일 대화방 **'아카라카북클럽'**(이하 북클럽 방)에 쌓인 대화를, **매주 화·토 07:00 KST**에
자동 요약해 네이버 **BAND**(https://www.band.us/band/102765569/post)에 **새 글**로 게시한다.

- 요약 대상은 **지난번 요약 이후의 새 대화만**(증분). 앞 주제와 이어지는 대화는 그 전 대화를
  **맥락 참고용**으로만 쓰되, 재요약하지 않는다.
- 요약에 포함할 항목:
  - 📅 향후 행사 일정/주제
  - 📚 추천 책 목록 — **책 이름 + 저자 필수**. 대화에 저자가 없으면 **웹검색**으로 보완(못 찾으면
    '저자 미상'으로 표기, 지어내지 않음).
  - 🔗 공유된 URL 목록 — 각 URL을 **방문해 페이지 제목**을 포함.
  - 🗣️ 주요 대화주제 + 참여 대화자.
- 게시 결과(성공/실패 + 미리보기)는 **텔레그램으로도 통지**한다.
- BAND 게시 시 **푸시 알림은 보내지 않는다**(`do_push=false`). 멤버 폰 알림을 피하기 위함이며,
  필요 시 코드 상수만 바꿔 켤 수 있다.

## 2. 전제 / 제약

1. **수집은 기존 파이프라인을 그대로 재사용한다.** 안드로이드 수집앱이 북클럽 방을 열 때 화면
   메시지를 긁어 Modal `kakao-ingest`로 보내고, `modal.Dict("kakao-collect")`에 14일 보존된다.
   본 설계는 **저장된 메시지를 읽어 요약·게시**하는 부분만 추가한다(수집 로직 변경 없음).
   - **의존 전제**: 수집앱의 대상 방 목록에 '아카라카북클럽'이 포함돼 실제 수집 중이어야 한다.
     아니면 요약할 데이터가 없다(배포/검증 시 1회 확인).
2. **증분 기준은 "수집 시각(`received_at`)"이다.** 기존 시스템과 동일한 의미. 폰이 절대 발신
   시각을 신뢰성 있게 만들 수 없어, 오늘 처음 스크롤한 옛 메시지도 "새 것"으로 들어올 수 있다.
   이 한계를 전제로 베스트-에포트 요약임을 받아들인다.
3. **요약/게시는 web+terminal 툴셋이 필요하다.** 책 저자 웹검색과 URL 제목 방문 추출 때문에,
   기존 `ai-cost-news`처럼 **Hermes cron job(web+terminal 에이전트)**으로 구현한다. Modal에 떠 있는
   Hermes를 재사용하므로 별도 요약 LLM 키가 필요 없다.
4. **BAND 발신은 외부 서비스로의 출력이다.** 액세스 토큰은 git에 두지 않고 Modal Secret으로 주입한다.

## 3. 전체 구조

```
[안드로이드 수집앱]  (기존, 변경 없음)
   · 북클럽 방을 열 때 화면 메시지를 긁어
   ▼  HTTPS POST /kakao-ingest
[Modal] modal.Dict("kakao-collect")  · 메시지 14일 보존 (기존)
   ▲  GET /kakao-messages?room=아카라카북클럽&since=14day   (기존 엔드포인트 재사용)
   │
[Modal] cron_tick  (기존, 매일 07:00·19:00 KST 컨테이너 깨움 → `hermes cron tick`)
   │  화·토 07:00 KST에 아래 Hermes cron job이 due → 실행
   ▼
[Hermes cron job] "카카오 북클럽 BAND 요약"  (신규, web+terminal)
   1) python ~/.hermes/scripts/kakao_bookclub_fetch.py
        → /kakao-messages 조회 → watermark 이후 '새 메시지' + '맥락 메시지' + cursor + URL목록(JSON)
   2) 새 메시지 없으면 BAND 게시 생략, 텔레그램에 "새 대화 없음"
   3) 있으면 풍부 추출(행사/책+저자(웹검색)/URL제목(방문)/주제·참여자)로 BAND 본문 작성
   4) python ~/.hermes/scripts/band_post.py  → POST openapi.band.us .../post/create (do_push=false)
        → result_code:1 확인 시에만 watermark 전진(~/.hermes/ 볼륨 파일)
   5) 텔레그램에 성공/실패 + 미리보기 (deliver: origin)
```

데이터 흐름의 핵심: **수집은 상시(기존), 요약·게시는 화·토 07:00 KST**, **범위는 watermark 이후 증분**.

## 4. 컴포넌트

### 4.1 증분 상태 — watermark (볼륨 파일)

- **저장 위치**: `~/.hermes/kakao_bookclub_watermark.json` (Hermes 볼륨 `hermes-home`).
  - cron_tick이 매 tick 끝에 `hermes_volume.commit()` 하므로, 에이전트가 tick 중 쓴 파일이
    커밋돼 화→토, 토→화 사이에 보존된다.
- **내용**: `{ "room": "아카라카북클럽", "cursor": "<ISO8601 UTC>", "updated_at": "<ISO8601>" }`.
  `cursor`는 **마지막으로 성공 게시한 배치의 최대 `received_at`**.
- **선택 이유 (Modal Dict 대비)**: 저용량·단일 소비자(cron)라 새 엔드포인트/예약키 없이 볼륨 파일로
  충분하다. 기존 `kakao-messages` 엔드포인트를 그대로 재사용해 modal_app.py 변경을 0으로 만든다.
  (대안: modal.Dict 예약키 + 신규 read/mark 엔드포인트 — 더 중앙화되지만 코드/배선이 늘어 보류.)
- **전진 규칙**: BAND 게시가 `result_code:1`로 성공한 **후에만** cursor를 갱신한다. 실패 시 그대로
  두어 다음 회차가 같은 구간을 재시도한다.
- **첫 실행(파일 없음)**: 현재 보관분(최근 14일) 전체를 대상으로 요약하고, 성공 시 watermark 생성.

### 4.2 순수 로직 — `scripts/kakao/collector_core.py`에 함수 추가 (유닛 테스트)

Modal 의존 없는 순수 함수로 추가해 단위 테스트한다.

- `filter_after_watermark(messages, cursor_iso) -> list[dict]`
  - `messages`(received_at 오름차순 가정 가능) 중 `received_at > cursor_iso`인 항목만 반환.
  - `cursor_iso`가 None/빈값이면 전체 반환(첫 실행).
- `next_cursor(messages) -> str | None`
  - 주어진 메시지들의 최대 `received_at`(ISO8601) 반환. 비면 None.
- `context_window(messages, cursor_iso, count) -> list[dict]`
  - watermark **이전** 메시지 중 가장 최근 `count`개를 "맥락용"으로 반환(재요약 금지 표시는 프롬프트가
    담당). cursor가 없으면 빈 리스트.
- 기존 `extract_urls(items)`는 그대로 재사용(결정적 URL 목록 → 링크 누락 방지).

### 4.3 조회 헬퍼 — `scripts/cron/kakao_bookclub_fetch.py` (신규, 유닛 테스트)

Hermes cron 에이전트가 가장 먼저 실행. `prepare_runtime.py`가 `~/.hermes/scripts/`로 동기화.

- 환경변수: `KAKAO_MESSAGES_URL`, `KAKAO_COLLECTOR_TOKEN`(기존), 방 이름 상수 `아카라카북클럽`.
- 동작:
  1. `GET {KAKAO_MESSAGES_URL}?token=...&room=아카라카북클럽&since=14day` 호출(기존 엔드포인트).
  2. watermark 파일 읽기 → `filter_after_watermark`로 새 메시지, `context_window`로 맥락 메시지,
     `next_cursor`로 cursor, `extract_urls`로 URL 목록 산출.
  3. stdout에 JSON 출력:
     ```json
     {
       "ok": true,
       "room": "아카라카북클럽",
       "new_count": 12,
       "cursor": "2026-06-21T00:10:00+00:00",
       "watermark_cursor": "2026-06-17T22:05:00+00:00",
       "new_messages": [ { "sender": "...", "text": "...", "client_time": "...", "received_at": "..." } ],
       "context_messages": [ ... ],
       "shared_urls": [ "https://...", ... ]
     }
     ```
  4. 조회 실패/빈 결과도 JSON으로 표면화(`ok:false` 또는 `new_count:0`).
- 순수 함수로 분리해 테스트: `build_url`(기존 `kakao_fetch.build_url` 패턴 재사용 가능).
- **이 스크립트는 watermark를 읽기만 한다.** 전진은 게시 성공 후 `band_post.py`가 수행(원자성↑).

### 4.4 BAND 게시 헬퍼 — `scripts/cron/band_post.py` (신규, 유닛 테스트)

- 환경변수: `BAND_ACCESS_TOKEN`, `BAND_KEY`(없으면 명확한 에러 JSON 후 비정상 종료).
- 입력: 게시 본문(파일 경로 `--content-file` 또는 stdin)과 `--cursor <ISO>`(성공 시 전진할 값),
  `--room`(watermark 파일에 기록).
- 동작:
  1. `POST https://openapi.band.us/v2.2/band/post/create`
     (form: `access_token`, `band_key`, `content`, `do_push=false`).
  2. 응답 `result_code == 1` 확인.
  3. 성공 시에만 `~/.hermes/kakao_bookclub_watermark.json`을 `--cursor`로 갱신.
  4. 결과 JSON을 stdout으로(`ok`, `result_code`, `post_key`, `watermark_advanced`).
- 순수 함수로 테스트: `build_post_payload(token, band_key, content, do_push) -> dict`,
  `is_success(response_json) -> bool`.
- **상수**: `DO_PUSH = False`(코드 한 줄 토글), 엔드포인트 URL, 게시 본문 길이 안전 상한(과도하게 길면
  말미를 잘라 단일 글 크기로 맞춤 — 정확한 한도는 문서 미공개라 보수적 상한 사용).

### 4.5 Hermes cron job — `cron_jobs/kakao-bookclub-band.json` (신규)

`ai-cost-news.json` 형식. 런타임에 생성 후 `apply_cron_jobs.py`로 관리, 본 파일로 버전관리.

- `schedule`: `0 22 * * 1,5` (UTC) = **화·토 07:00 KST**. (검증: `0 22 * * 5`=토 07:00 KST.)
- `repeat`: `forever`, `enabled`: true, `deliver`: `origin`(텔레그램 상태 통지).
- `enabled_toolsets`: `["web", "terminal"]`.
- `script`: null(프롬프트가 터미널로 헬퍼 2개를 직접 실행).
- `prompt`(요지):
  1. `python ~/.hermes/scripts/kakao_bookclub_fetch.py` 실행 → JSON 파싱.
  2. `ok:false`면 텔레그램에 오류 보고 후 종료(게시 안 함).
  3. `new_count == 0`이면 **BAND 게시하지 말고** 텔레그램에 "이번 회차에 새 대화가 없습니다"만 알림.
  4. `new_messages`만 요약 대상으로 삼고, `context_messages`는 **맥락 이해용(재요약 금지)**.
  5. BAND 본문 구성(평문, BAND는 URL을 자동 링크로 렌더):
     - 제목/머리말: `📖 아카라카북클럽 대화 요약` + 기준(예: `기준: 지난 요약 이후 새 대화 N건`).
     - `📅 향후 행사 일정/주제`
     - `📚 추천 책`: 각 항목 `『제목』 — 저자`. 대화에 저자 없으면 **웹검색으로 보완**, 못 찾으면
       `저자 미상`(지어내지 않음). 없으면 섹션 생략.
     - `🔗 공유된 링크`: `shared_urls`의 각 URL을 **방문해 페이지 제목**을 붙여 `제목 — URL`. 방문
       실패 시 제목 없이 URL만. 없으면 섹션 생략.
     - `🗣️ 주요 대화주제와 참여자`: 화제별로 누가 무엇을 말했는지.
  6. `python ~/.hermes/scripts/band_post.py --cursor <fetch의 cursor> --room 아카라카북클럽` 로
     본문 게시. 출력 JSON 확인.
  7. 텔레그램 최종 메시지: 게시 성공/실패 + 본문 앞부분 미리보기.
  - 제약: 메시지에 없는 내용 지어내지 말 것; 새 대화(`new_messages`)만 근거; 일부 유실 가능성
    전제로 단정 회피; cron 안에서 새 cron 만들거나 수정하지 말 것.

### 4.6 시크릿/환경 배선

- `scripts/create_modal_secret.py`: `COPY_ENV_KEYS`에 `BAND_ACCESS_TOKEN`, `BAND_KEY` 추가.
  (외부 자격증명 → 자동생성 안 함. env 또는 `~/.hermes/.env`에 있으면 secret으로 복사.)
- `scripts/prepare_runtime.py` `write_env_file()`의 `keys`에 `BAND_ACCESS_TOKEN`, `BAND_KEY` 추가
  (컨테이너 env → `~/.hermes/.env` → 에이전트 셸/헬퍼가 읽음). `KAKAO_MESSAGES_URL`/
  `KAKAO_COLLECTOR_TOKEN`는 이미 포함.
- **modal_app.py 변경 없음**(신규 엔드포인트/스케줄 불필요).

## 5. BAND 앱 등록 / 토큰 발급 (1회, 수동)

1. https://developers.band.us/develop/myapps/list 에서 앱 생성.
   - **Redirect URI**: `http://localhost:8080/` (BAND 공식 샘플이 쓰는 값. 콘솔 칸은 "도메인"을
     받으며, 서버 cron은 실행 시점에 콜백이 필요 없다).
2. 앱의 **Access Token** 섹션에서 **"밴드 계정 연동"** 버튼으로 본인 계정 토큰을 발급(콘솔에 바로
   표시 — 콜백 왕복 불필요). 토큰 수명 ≈ 10년(`expires_in: 315359999`)이라 cron에서 갱신 불필요.
3. 최초 1회 `GET https://openapi.band.us/v2.1/bands?access_token=<토큰>`로 대상 밴드의 `band_key`
   확인(이 키는 URL의 `102765569`와 다른 API용 키). 필요 권한 scope: `WRITE_POST`(기본 부여).
4. `BAND_ACCESS_TOKEN`, `BAND_KEY`를 `~/.hermes/.env`에 넣고
   `python scripts/create_modal_secret.py`로 secret에 반영 후 `modal deploy`.

## 6. 데이터 모델

수집 메시지(기존):
```json
{ "room": "아카라카북클럽", "sender": "홍길동", "text": "본문", "client_time": "오후 3:25",
  "received_at": "2026-06-21T00:10:00+00:00" }
```
watermark 파일(신규):
```json
{ "room": "아카라카북클럽", "cursor": "2026-06-21T00:10:00+00:00", "updated_at": "2026-06-21T22:00:05+00:00" }
```

## 7. 엣지 케이스 / 한계

- **새 대화 없음**: BAND 게시 생략(빈 글 안 만듦), 텔레그램만 통지, watermark 불변.
- **BAND 게시 실패**: watermark 불변 → 다음 회차 같은 구간 재시도. (드물게 부분 실패 후 다음 성공으로
  중복 게시 가능 — 주 2회라 허용. band_post는 `result_code:1` 확인 시에만 전진.)
- **첫 실행**: watermark 없음 → 보관분(최근 14일) 전체 요약 후 watermark 생성.
- **저자 미확인**: 웹검색 후에도 못 찾으면 '저자 미상'(지어내지 않음).
- **URL 제목 실패**: 방문 불가 시 제목 없이 URL만 표기.
- **사진/스티커**: `[사진]` 등 텍스트로만 들어옴 → 텍스트 위주 요약.
- **메시지 유실**: 수집은 "방을 연 범위"만 → 베스트-에포트, 단정 회피.
- **프롬프트 크기**: 새 메시지가 많으면 최근 N건(예: 600)으로 상한.
- **수집 미가동**: 수집앱이 북클럽 방을 안 잡고 있으면 항상 `new_count:0` → 텔레그램 통지로 조기 발견.

## 8. 테스트 계획

1. **유닛**: `filter_after_watermark`/`next_cursor`/`context_window`(collector_core),
   `band_post.build_post_payload`/`is_success`, `kakao_bookclub_fetch.build_url`.
2. **헬퍼 수동**: 환경변수 세팅 후 `kakao_bookclub_fetch.py` 실행 → JSON 형태/필터 확인.
   `band_post.py`로 테스트 밴드(또는 do_push=false)로 1회 게시 → BAND에 글 확인, watermark 갱신 확인.
3. **cron 단발**: `hermes cron run <job_id>` → BAND 게시 + 텔레그램 통지 확인. 두 번째 실행 시
   `new_count:0`(증분 동작) 확인. 빈 구간일 때 게시 생략 확인.
4. **전체 테스트**: `python -m pytest tests/ -v` 통과.

## 9. 범위 밖 / 향후

- 다중 방, 미디어 본문 처리.
- watermark를 modal.Dict로 이전(다중 소비자 필요 시).
- BAND 댓글/이미지 첨부 게시.
- 게시 실패 자동 재시도 큐(현재는 다음 회차 자연 재시도).

## 10. 산출물 요약

- 수정: `scripts/kakao/collector_core.py`(순수함수 3개), `scripts/create_modal_secret.py`,
  `scripts/prepare_runtime.py`, `README.md`, `cron_jobs/README.md`.
- 신규: `scripts/cron/kakao_bookclub_fetch.py`, `scripts/cron/band_post.py`,
  `cron_jobs/kakao-bookclub-band.json`,
  `tests/test_kakao_bookclub_fetch.py`, `tests/test_band_post.py`,
  `tests/test_kakao_collector_core.py`(케이스 추가).
- modal_app.py: 변경 없음.
