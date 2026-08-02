# LongBlack note extraction for knowledge repo edits

Use when the user provides a LongBlack article URL or a Google share URL that resolves to LongBlack.

## Reliable steps

1. Resolve `share.google/...` with redirects and use the canonical `https://longblack.co/note/<id>` URL.
2. Fetch the note HTML directly with a browser-like User-Agent.
3. Extract these fields before summarizing:
   - `<title>` / `og:title`
   - `<meta name="description">`
   - visible text after stripping scripts/styles; LongBlack often exposes title, author, source, date, intro, and the first Q&A section even when the rest is gated.
   - JSON-LD `datePublished` for ISO publication time.
4. If asked to find a companion/prequel/sequel article:
   - Check `https://longblack.co/feed.xml` first; it often contains recent note titles, URLs, dates, and descriptions.
   - If the companion is not in the feed, probe nearby numeric note IDs and compare titles/descriptions for the same people/topic.
5. Ground summaries only in accessible metadata/intro/visible text. If the full article is gated, state that the note was based on publicly accessible title/meta/intro content.

## Example pattern

For a user-provided Google share URL resolving to `https://longblack.co/note/2069`, the companion prequel was found by probing nearby note IDs: `https://longblack.co/note/2062` had the matching title `앤트로픽을 최강의 AI 기업으로 만든 남자, 보리스 체르니 인터뷰 (전편)` and visible intro/date metadata.
