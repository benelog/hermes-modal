# YouTube metadata fallback for benelog notes

## Context

When adding YouTube links to benelog notes, a short summary should be grounded in the video's title, description, or chapters. In Modal/cloud environments, direct transcript extraction can fail:

- `yt-dlp` may report: `Sign in to confirm you’re not a bot`.
- `youtube-transcript-api` may raise `RequestBlocked` because many cloud-provider IPs are blocked by YouTube.

## Useful fallback sequence

1. Fetch official oEmbed metadata for the title/channel:

```bash
python3 - <<'PY'
import urllib.request
url='https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=VIDEO_ID&format=json'
print(urllib.request.urlopen(url, timeout=20).read().decode())
PY
```

2. Fetch a markdown rendering via Jina Reader. The double `r.jina.ai/http://...` form has worked for YouTube watch pages and can expose description and chapters even when direct YouTube access is limited:

```bash
curl -L --silent --max-time 90 \
  'https://r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' \
  | sed -n '1,220p'
```

3. Grep for key sections if the output is long:

```bash
curl -L --silent --max-time 90 \
  'https://r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' \
  | grep -i -A2 -B2 -E 'Description|Chapters|①|②|③|④|plugin|MCP|Report|Hook'
```

## Example learning from `L94yAQR9VvA`

The Jina output exposed title, chapters, and description for a video about four official Claude Code plugins:

- Session Report: visualizes token usage / prompt-cache effects as an HTML report.
- Claude MD Management: cleans and grades bloated `CLAUDE.md` files.
- Serena MCP: uses semantic code search to reduce repeated text-search token waste.
- Hookify: blocks repeated Claude mistakes with hooks as a harness-engineering practice.

Use these as examples of the level of detail appropriate for a compact note, not as universal plugin descriptions for unrelated videos.
