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

2. Fetch a markdown rendering via Jina Reader. The double `r.jina.ai/http://...` form has worked for YouTube watch pages and can expose description and chapters even when direct YouTube access is limited. Do not rely only on the first screenful: YouTube/Jina output can repeat metadata and place the full description or later chapters several hundred lines down.

```bash
curl -L --silent --max-time 90 \
  'https://r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' \
  | sed -n '1,260p'
```

If chapter lists appear incomplete, inspect lower ranges too:

```bash
tmp=$(mktemp)
curl -L --silent --max-time 90 \
  'https://r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' > "$tmp"
sed -n '700,860p' "$tmp"
rm "$tmp"
```

3. Grep for key sections if the output is long:

```bash
curl -L --silent --max-time 90 \
  'https://r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' \
  | grep -i -A2 -B2 -E 'Description|Chapters|①|②|③|④|plugin|MCP|Report|Hook|Speakers|Presented|prod|vibe'
```

4. If the double-reader URL exposes only partial metadata, try the single-reader variants too. For some YouTube pages they surface the same description block plus comments/recommendations in a different order, which can help confirm title, event, date, speaker, and community reaction without a transcript:

```bash
for u in \
  'https://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' \
  'https://r.jina.ai/http://http://www.youtube.com/watch?v=VIDEO_ID' \
  'https://r.jina.ai/http://https://m.youtube.com/watch?v=VIDEO_ID'; do
  echo "--- $u"
  curl -L -sS --max-time 60 "$u" \
    | grep -i -A3 -B3 -E 'Description|Chapters|Speakers|Presented|Claude Code|production|prod|vibe'
done
```

5. Treat YouTube comments as weak evidence only. They can support notes like “audience reaction included concerns about production use,” but should not be used as authoritative facts about the talk. When transcript access is blocked and the description is truncated (e.g. `Speakers: …`), keep the summary at the metadata level and mention the limitation in the final response.

6. If you need to slice/inspect the Jina output with Python, save it to a temp file or pass Python code with `-c`. Do **not** combine a pipe with `python3 - <<'PY' ... PY` and then call `sys.stdin.read()`; the heredoc consumes stdin for the code, so the piped Jina content is lost and output can appear empty even though `curl | wc -c` shows bytes.

```bash
tmp=$(mktemp)
curl -L --silent --max-time 90 \
  'https://r.jina.ai/http://r.jina.ai/http://https://www.youtube.com/watch?v=VIDEO_ID' > "$tmp"
sed -n '1,180p' "$tmp"
rm "$tmp"
```

## Example learning from `L94yAQR9VvA`

The Jina output exposed title, chapters, and description for a video about four official Claude Code plugins:

- Session Report: visualizes token usage / prompt-cache effects as an HTML report.
- Claude MD Management: cleans and grades bloated `CLAUDE.md` files.
- Serena MCP: uses semantic code search to reduce repeated text-search token waste.
- Hookify: blocks repeated Claude mistakes with hooks as a harness-engineering practice.

Use these as examples of the level of detail appropriate for a compact note, not as universal plugin descriptions for unrelated videos.
