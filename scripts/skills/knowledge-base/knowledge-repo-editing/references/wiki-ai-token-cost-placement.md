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

## Source extraction note

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
