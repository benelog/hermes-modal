# Book title/author enrichment for ABC weekly summaries

Use this reference when shared items look book-related but the collected KakaoTalk text lacks either the title or the author.

## Required resolution order

1. **Message text clues first**
   - Recognize quoted titles in forms like `《...》`, `『...』`, single/double quotes, and Korean smart quotes.
   - Recognize author clues such as `OO 작가`, `OO 작가님`, `OO 저자`, `OO 저자님`, `OO 셰프/쉐프`.
   - Preserve uncertainty: if only an author or only a title is present, do not invent the missing side.

2. **Visit and parse shared URLs**
   - Fetch the shared URL and parse `og:title`, `twitter:title`, `<title>`, `book:author`, `article:author`, and generic `author` metadata.
   - Apply bookstore-specific parsing when possible:
     - YES24 titles often appear as `책 제목 | 저자 | 출판사 - 예스24`.
     - 알라딘/교보 pages often expose usable Open Graph title or JSON/markup author fields.
   - Keep the URL in the final URL list even if parsing fails or is rate-limited.

3. **Internet/bookstore search**
   - If an author is recognized but title is missing, search for `author + 책 + surrounding context`.
   - If a title is recognized but author is missing, search for `title + 책 저자`.
   - Prefer bookstore, publisher, library, or author/publisher pages over generic snippets.
   - Label search-derived matches as inferred/confirmed from search evidence, not as raw message text.

4. **OCR associated image attachments last**
   - Attempt OCR only when the collector includes an actual image URL/path field, e.g. `image_url`, `attachments`, `media`, `files`, `photo_url`, or local image path.
   - If the collected text only says `[사진]`, `사진`, or `이미지` and no image payload is available, state that OCR was impossible because the image was not collected.
   - Do not infer a book title/author solely from an unavailable image placeholder.

## Helper script

The weekly cron should run or consume `/root/.hermes/scripts/abc_book_enrichment.py` before final summary writing. The Git-tracked source is `scripts/cron/abc_book_enrichment.py` in `benelog/hermes-modal`.

Typical commands:

```bash
python /root/.hermes/scripts/abc_book_enrichment.py --since 9day --start YYYY-MM-DD --end YYYY-MM-DD
python /root/.hermes/scripts/abc_book_enrichment.py --input-json /tmp/kakao_abc_weekly.json --start YYYY-MM-DD --end YYYY-MM-DD
```

The script emits JSON with:

- filtered ABC message count (using original KakaoTalk send date: `sent_time` date when available, otherwise legacy `client_time` date; never `received_at`),
- deduplicated URLs and parsed page/book metadata,
- book candidate lines,
- message title/author clues,
- web search results for missing title/author cases,
- YES24 purchase-link candidates (`yes24_purchase_candidates`) when the shared conversation did not include a purchase URL,
- OCR output or explicit OCR-unavailable errors for image placeholders.

## Final-summary rule

Before finalizing, cross-check the final books section against:

- the helper JSON `urls[*].book`,
- `book_candidates[*].message_titles`,
- `book_candidates[*].message_authors`,
- `book_candidates[*].web_search_results`,
- the deduped URL list.

If the title or author still cannot be established after the required order, write `제목 미확인` or `저자 미확인` plainly rather than guessing.
