---
name: abc-weekly-summary
description: 매주 월요일 오전 7시에 카카오톡 ABC(아카라카북클럽) 대화방의 지난주 월요일부터 전날 일요일까지 대화를 책과 URL 중심으로 plain text 요약한다.
---

# ABC weekly KakaoTalk summary

## Trigger

Use when the user asks for a weekly summary of the KakaoTalk ABC / ABC(아카라카북클럽) room, especially for Band copy/paste output focused on shared books and URLs.

## Scope

- Target room: `ABC(아카라카북클럽)`.
- Default reporting window for the Monday 07:00 recurring job: previous Monday 00:00 through previous Sunday 23:59:59 in Asia/Seoul.
- For ad hoc requests, resolve relative dates with `TZ=Asia/Seoul date` first.
- Use `client_time[:10]` to filter the requested explicit date range. Do not rely only on `--since`, because the collector's `--since` is based on collection time.

## Procedure

1. Confirm current date/time in Korea:
   ```bash
   TZ=Asia/Seoul date '+%Y-%m-%d %A %H:%M:%S %Z %z'
   ```
2. Compute the inclusive date range. For a Monday weekly run, summarize the 7-day period from last Monday through yesterday Sunday.
3. Fetch a sufficiently wide collector window:
   ```bash
   python /root/.hermes/scripts/kakao_fetch.py --since 9day > /tmp/kakao_abc_weekly.json
   ```
4. Parse JSON and filter messages where:
   - `room == "ABC(아카라카북클럽)"`
   - `start_date <= client_time[:10] <= end_date`
5. Extract and deduplicate URLs with `https?://\S+`, stripping trailing punctuation. For each URL, fetch the page title or Open Graph title when feasible. Keep the URL even if title fetching fails.
6. Extract book/reading candidates from message text using terms such as `책`, `도서`, `읽`, `추천`, `소설`, `작가`, `저자`, `출판`, `서점`, `문학`, `독서`, `알라딘`, `교보`, `yes24`, `예스24`, `《`, `『`, `에세이`, `시집`, `만화`, `서평`.
7. For book-like URLs or image-only shares where the collector text does not include a title, try to infer title and author from:
   - fetched URL title / OG title,
   - neighboring messages,
   - explicit author clues in the thread.
   If the actual image is not available in the collected text, state plainly that the image itself was not available and only the surrounding conversation was used.
8. Cross-check final output against the URL list and book candidate lines so no shared book, author, or URL is omitted.

## Output rules

- Write in Korean.
- Plain text first: suitable for copy/paste into Band.
- Do not include emoji in the body.
- Prefer simple headings and hyphen bullets.
- Put shared/recommended books first, then deduped URLs, then key topics.
- For books, include author whenever available. If author is unavailable, write `저자 미확인` rather than guessing.
- Mention the message count and any collection caveat briefly.
- Do not list raw messages verbatim; summarize with attribution/context.

## Suggested output shape

```text
ABC 대화방 주간 요약
기간: YYYY-MM-DD(월) ~ YYYY-MM-DD(일)
기준: 수집된 ABC(아카라카북클럽) 메시지 N건
참고: 수집된 메시지 기준입니다. 이미지 원본이 수집되지 않은 경우 주변 대화와 URL 제목만 근거로 삼았습니다.

1. 공유·추천된 책 / 작가 / 읽을거리

- 《책 제목》 - 저자
  - 맥락 요약.
  - 링크: https://...

- 제목 미확인 책/이미지 공유 - 저자 미확인
  - 이미지 원본이 없어 제목 확인은 불가. 주변 대화상 ...로 추정.

2. 공유된 URL 모음

- 페이지 제목 또는 확인된 설명
  - 맥락: ...
  - URL: https://...

3. 핵심 화제 요약

- 화제 제목
  - 세부 내용.

4. 결정·일정·약속

- 일자/장소/할 일.

5. 한 줄 분위기

...
```

## Pitfalls

- `--room ABC` may return zero if the stored room is `ABC(아카라카북클럽)`; fetch broadly and filter locally before concluding there is no data.
- Avoid decorative emoji because the user wants Band-friendly plain text and no emoji in the body.
- Do not fabricate titles or authors for image-only shares. Label inference and uncertainty explicitly.
- If URL title fetching is rate-limited, use surrounding message text as the title/description and note only when important.
