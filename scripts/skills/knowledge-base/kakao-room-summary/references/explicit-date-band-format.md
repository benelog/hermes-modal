# Explicit date range + Band-friendly ABC summary workflow

Use this reference when the user asks for a KakaoTalk ABC/아카라카북클럽 summary over a concrete date range and wants a Band copy/paste-ready result.

## Retrieval and filtering

1. Confirm the current date with `date` when the request contains relative endpoints such as "어제".
2. Fetch a wide enough window from the collector. If a room-specific fetch returns 0, retry without `--room` and filter locally:
   ```bash
   python /root/.hermes/scripts/kakao_fetch.py --since 9day > /tmp/kakao_all.json
   ```
3. Filter messages by:
   - `room == "ABC(아카라카북클럽)"`
   - `client_time[:10]` within the requested inclusive date range
4. Report the filtered count and mention when some dates in the requested range had no collected messages.

## Extraction checklist before summarizing

Run or manually perform these extractions before writing the final summary:

- Deduped URL list from `text` using `https?://\S+`; strip trailing punctuation.
- For each URL, fetch page title/OG title when feasible. Keep the URL even if the title fetch fails.
- Book/reading candidates using terms such as `책`, `도서`, `읽`, `추천`, `소설`, `작가`, `저자`, `출판`, `서점`, `문학`, `독서`, `알라딘`, `교보`, `yes24`, `《`, `『`.
- Cross-check final text against the candidate lines so recommended books, authors, interviews, and links are not lost.

## Band copy/paste output shape

Prefer plain Markdown that remains readable after copy/paste:

```markdown
## ABC 대화방 요약
기간: YYYY-MM-DD ~ YYYY-MM-DD
기준: 수집된 ABC(아카라카북클럽) 메시지 N건
※ 필요한 caveat: 특정 날짜 메시지 없음 / 수집본 기준 등

---

## 📚 추천·언급된 책 / 작가 / 읽을거리

- **《책 제목》 — 저자**
  - 누가 어떤 맥락에서 추천/후기/언급했는지.
  - 링크: https://...

- **제목 미확인 추천/구입 책**
  - 제목이 수집 텍스트에 없으면 없다고 명시하고 맥락만 요약.

---

## 🔗 공유된 링크 모음

1. **페이지 제목**  
   맥락 한 줄.  
   https://...

---

## 핵심 화제 요약

**1. 화제 제목**
- 세부 내용.
- 결정/약속/장소/일정은 빠뜨리지 않기.

---

## 한 줄 분위기 요약
...
```

## Pitfalls

- The collector's `--since` is based on collection time, not message send time; explicit date requests require `client_time` filtering.
- `--room ABC` may return 0 even when the stored room is `ABC(아카라카북클럽)`. Retry broad fetch + local room filtering before concluding no data.
- Do not put generic topic bullets before recommended books when the user says "추천된 책, 링크를 누락하지 말고"; books/links should be near the top.
