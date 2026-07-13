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
- Filter the requested explicit date range by the original KakaoTalk send date, not the Modal server receive/collection date. Prefer `sent_time[:10]` when `sent_time` contains an ISO date/datetime; otherwise fall back to `client_time[:10]` because older collector records store the KakaoTalk-displayed send date there. Never use `received_at` for weekly date filtering. Do not rely only on `--since`, because the collector's `--since` is based on collection time.

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
   - `start_date <= sent_date <= end_date`, where `sent_date` is the original KakaoTalk send date: prefer `sent_time[:10]` when `sent_time` contains an ISO date/datetime, otherwise fall back to `client_time[:10]` for older records. Do **not** filter by `received_at`, which is the Modal server collection timestamp.
5. Run the enrichment helper before writing the summary whenever available:
   ```bash
   python /root/.hermes/scripts/abc_book_enrichment.py --since 9day --start YYYY-MM-DD --end YYYY-MM-DD
   ```
   If working from an already fetched JSON file, use:
   ```bash
   python /root/.hermes/scripts/abc_book_enrichment.py --input-json /tmp/kakao_abc_weekly.json --start YYYY-MM-DD --end YYYY-MM-DD
   ```
6. Extract and deduplicate URLs with `https?://\S+`, stripping trailing punctuation. For each URL, fetch the page title or Open Graph title when feasible. Keep the URL even if title fetching fails.
7. Extract book/reading candidates from message text using terms such as `책`, `도서`, `읽`, `추천`, `소설`, `작가`, `저자`, `출판`, `서점`, `문학`, `독서`, `알라딘`, `교보`, `yes24`, `예스24`, `《`, `『`, `에세이`, `시집`, `만화`, `서평`.
8. For title-missing or author-missing book candidates, resolve title and author in this order and record uncertainty instead of guessing:
   1. **Author/title clue recognition from message text**: capture quoted titles (`《...》`, `『...』`, quotes) and author clues such as `OO 작가`, `OO 저자`, `OO 셰프`.
   2. **Shared URL visit and parsing**: open shared bookstore/article/search URLs; parse `og:title`, `<title>`, `book:author`, `author`, and bookstore-specific fields such as YES24/알라딘/교보 title-author markup.
   3. **Internet/bookstore search**: when an author is recognized but the title is missing, search with `author + 책 + surrounding context`; when a title is recognized but the author is missing, search with `title + 책 저자`. Prefer bookstore/library/publisher pages over snippets.
   4. **Associated attachment image OCR**: only after text/URL/search are insufficient, OCR any actual image URL/path present in collector fields such as `image_url`, `attachments`, `media`, or `files`. If the collector only says `[사진]` and no image file/URL is available, explicitly say OCR was impossible because the image payload was not collected.
9. Cross-check final output against the enrichment helper output, the URL list, and book candidate lines so no shared book, author, purchase link, or non-book URL is omitted. Attach book-related URLs to the relevant book entry and exclude them from the URL section to avoid duplication.

## Output rules

- Write in Korean.
- Plain text first: suitable for copy/paste into Band.
- Do not include emoji in the body.
- Prefer simple headings and hyphen bullets.
- Put shared/recommended books first, then deduped non-book URLs, then exactly 10 core keywords.
- For books, include author whenever available. If author is unavailable after message-text clue recognition, URL parsing, internet/bookstore search, and available image OCR, write `저자 미확인` rather than guessing.
- Mention the message count and any collection caveat briefly.
- Do not list raw messages verbatim; summarize with attribution/context.

## Suggested output shape

```text
ABC 대화방 주간 요약
기간: YYYY-MM-DD(월) ~ YYYY-MM-DD(일)
기준: 수집된 ABC(아카라카북클럽) 메시지 N건
참고: 수집된 메시지 기준입니다. 이미지 원본이 수집되지 않은 경우 주변 대화와 URL 제목만 근거로 삼았습니다.

## 공유·추천된 책

### 《책 제목》 - 저자

- 맥락 요약.
- 구매 링크: https://m.yes24.com/...

### 제목 미확인 책/이미지 공유 - 저자 미확인

- 이미지 원본이 없어 제목 확인은 불가. 주변 대화상 ...로 추정.
- 구매 링크: 확인 불가

## 공유된 URL 모음

### 페이지 제목 또는 확인된 설명
- 맥락: ...
- URL: https://...

## 핵심 키워드 10개

- 키워드1
- 키워드2
- 키워드3
- 키워드4
- 키워드5
- 키워드6
- 키워드7
- 키워드8
- 키워드9
- 키워드10
```

Additional output rules for this shape:

- Do not include separate `핵심 화제 요약`, `결정·일정·약속`, or `한 줄 분위기` sections. The purpose is to keep the report centered on books and shared URLs.
- Use heading levels rather than indentation because Band copy/paste does not preserve indentation clearly.
- For every identified book, include `구매 링크:`. If the purchase link was not shared in the Kakao room, search the internet and fill it with a YES24 URL whenever a confident YES24 product page is found. Prefer mobile YES24 URLs (`https://m.yes24.com/goods/detail/...`) when available.
- If a book's URL is already used as `구매 링크` in `## 공유·추천된 책`, remove that URL from `## 공유된 URL 모음` entirely. Put all relevant conversation context for that book into the book's `맥락` bullets to avoid duplication.
- `## 공유된 URL 모음` should contain only non-book URLs or book-related URLs that could not be confidently attached to a specific book entry.

## References

- `references/2026-07-abc-weekly-cron-setup.md` records the initial weekly cron setup pattern, including the KST-to-UTC schedule conversion, self-contained cron prompt checklist, and Git-tracked cron JSON verification steps.
- `references/book-title-author-enrichment.md` records the required message-clue → URL parsing → internet/bookstore search → available-image OCR sequence and the `abc_book_enrichment.py` helper contract.
- `references/manual-test-run.md` records the reliable manual test-run pattern: trigger the cron job, run a scheduler tick, then verify saved output and delivery logs.

## Pitfalls

- `--room ABC` may return zero if the stored room is `ABC(아카라카북클럽)`; fetch broadly and filter locally before concluding there is no data.
- Avoid decorative emoji because the user wants Band-friendly plain text and no emoji in the body.
- Do not fabricate titles or authors for image-only shares. Follow the resolution order: message author/title clues → URL parsing → internet/bookstore search → OCR of actual collected image attachments. Label inference and uncertainty explicitly.
- If URL title fetching is rate-limited, use surrounding message text as the title/description and note only when important.
- For the user's Monday 07:00 KST weekly cron, use the UTC cron expression `0 22 * * 0` unless the active runtime explicitly supports timezone-aware cron expressions; `0 7 * * 1` schedules 07:00 UTC in the observed Hermes runtime.
- For manual review runs, do not stop after `cronjob(action="run")` or `hermes cron run`; that only schedules the job for the next scheduler tick. Run `hermes cron tick`, verify `last_status`/saved output, and only then tell the user it completed.
