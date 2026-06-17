# Wiki ai-token-cost placement

Use this when adding AI token cost / tokenmaxxing / AI FinOps / agent token consumption news to the user's wiki.

## Target page

- Preferred file: `/root/workspace/benelog/wiki/content/ai-token-cost.md`
- Public page: `https://wiki.benelog.net/ai-token-cost`
- It is linked from `wiki/content/ai-trend.md` under `## Token 사용 경향` as `[[ai-token-cost]]`.

## Placement convention

- Add news links under the month heading, e.g. `## 2026년 5월`.
- Keep reverse chronological order by publication date; newest items go first within the month.
- Existing style:
  - Top-level bullet: `* [Title](URL) (Source, YYYY.M.D)`
  - Summary: 3–5 nested bullets indented with four spaces.
  - Korean, concise, grounded in retrieved article text/metadata.

## Source extraction notes

For ajunews.com pages, Jina Reader worked well with:

```bash
python - <<'PY'
import requests
url='https://r.jina.ai/http://https://www.ajunews.com/view/ARTICLE_ID'
text=requests.get(url,timeout=30).text
for i,line in enumerate(text.splitlines(),1):
    if START <= i <= END:
        print(f'{i}: {line}')
PY
```

In the 2026-05-29 article, the main body appeared around lines 137–199 of the Jina output after navigation boilerplate.

For Korean news pages where `bs4` is unavailable in the execution environment, use direct `requests` plus stdlib regex/html cleanup to extract `og:title`, `og:description`, `article:published_time`, `<title>`, and article-ish blocks; also fetch Jina Reader output as a fallback/cross-check. This worked for:

- `www.aitimes.com/news/articleView.html?idxno=211728`: direct page exposed title, description, `2026-06-15T17:57:56+09:00`, and enough body text; add under `## 2026년 6월` as AI subscription/token-cost economics.
- `news.einfomax.co.kr/news/articleView.html?idxno=4420028`: direct page exposed title, description, `2026-06-15T14:53:55+09:00`, and body; add under `## 2026년 6월` as AI spend controls / AI diet / token governance.
- `news.mtn.co.kr/news-detail/2026061513272690395`: direct page exposed title, description, `2026-06-15 14:58:48`, and body; add under `## 2026년 6월` as frontier model token-price increases and orchestration/cost management.

Minimal extraction sketch:

```python
import html, re, requests
headers = {'User-Agent': 'Mozilla/5.0'}
text = requests.get(url, headers=headers, timeout=15).text
# regex meta extraction for og:title/og:description/article:published_time
# strip script/style/tags with re, then collapse whitespace
jina = requests.get('https://r.jina.ai/http://' + url, timeout=25).text
```
