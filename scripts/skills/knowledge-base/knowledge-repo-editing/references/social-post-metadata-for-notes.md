# Social post metadata fallback for benelog notes

Use when adding X/Twitter/LinkedIn/social posts to benelog knowledge repos and the original page is login-gated or sparse.

## X/Twitter sequence

1. Try official oEmbed for author/date/link previews:

```bash
python3 - <<'PY'
import urllib.request
url='https://publish.twitter.com/oembed?url=https://x.com/USER/status/POST_ID'
print(urllib.request.urlopen(url, timeout=30).read().decode())
PY
```

2. If Jina Reader blocks `x.com`, retry with the equivalent `twitter.com` URL. This can expose full long-post/article text, publish time, images/figures, and outbound links even when `x.com` returns a 403 abuse/rate-limit response:

```bash
curl -L -sS --max-time 90 \
  'https://r.jina.ai/http://https://twitter.com/USER/status/POST_ID'
```

3. If the post is only a `t.co` link, resolve it separately:

```bash
curl -Ls -o /dev/null -w '%{url_effective}\n%{http_code}\n' 'https://t.co/SHORTCODE'
```

4. Ground notes in retrieved post/article text and metadata. If only oEmbed is available, keep the summary minimal and mention the limitation in the final response.

## Placement example from Uber Engineering post

An X post at `https://x.com/UberEng/status/2093444169037762840` looked sparse via oEmbed, but Jina Reader on `twitter.com/UberEng/status/...` exposed a long article-style post about Uber's Software Factory and AI coding cost optimization. It belonged in `devnote/content/ai-productivity.md` under `## 사례/연구`, with a summary of adoption metrics, agent skills, model routing, context/token savings, CLI gateway/tool search/code-mode, AI Context Graph, and cost visibility dashboards.
