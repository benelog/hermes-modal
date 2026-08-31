# Wiki general YouTube link placement

Use when the user asks to add a non-development YouTube/video URL to the `benelog/wiki` repo without naming a page.

## Pattern from Seoul free travel video

For a general lifestyle/travel video such as `https://www.youtube.com/watch?v=2GFvuQjDw14`:

1. Fetch YouTube oEmbed for Korean title/channel, and Jina Reader for date/description. If transcript endpoints return empty or YouTube blocks with bot checks, keep the summary grounded in oEmbed + description only.
2. Search `wiki/content` for an existing topic page. If no specific page exists, create a class-level page under `content/<topic>.md` rather than forcing the item into `free-contents.md` or an unrelated page.
3. Link the new topic page from an existing higher-level hub page when there is one. Example: create `content/여행.md` and add `[[여행]]` to `content/취미.md`.
4. Use the existing wiki bullet style:
   - Top-level bullet: `* [title](url) - channel/source, YYYY-MM-DD`
   - Nested bullets: 3 concise Korean bullets grounded in source metadata/description.
5. For deployed URLs, wiki `content/<slug>.md` maps to `https://wiki.benelog.net/<slug>`; include that URL in final responses when citing the page.

## Pitfall

Do not over-interpret a video when transcript access is unavailable. Say the summary is based on title/channel/date/description metadata, and avoid claiming the exact list of all locations unless the source metadata explicitly exposes them.
