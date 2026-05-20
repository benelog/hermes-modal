# Naver News extraction for knowledge-repo edits

Use this when the user asks to add an `n.news.naver.com` article to a benelog knowledge repository, especially when exact quoted wording must be verified.

## Minimal dependency-free extraction

```bash
python3 - <<'PY'
import urllib.request, re, html
url='https://n.news.naver.com/mnews/article/...'
text=urllib.request.urlopen(
    urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'}),
    timeout=20,
).read().decode('utf-8','replace')

fields={}
for name,pat in [
    ('title', r'<meta property="og:title" content="([^"]+)"'),
    ('desc', r'<meta property="og:description" content="([^"]*)"'),
    ('author', r'<meta property="og:article:author" content="([^"]*)"'),
    ('date', r'data-date-time="([^"]+)"'),
]:
    m=re.search(pat,text)
    fields[name]=html.unescape(m.group(1)) if m else ''
print(fields)

m=re.search(r'<article[^>]*id="dic_area"[^>]*>(.*?)</article>', text, re.S)
if not m:
    m=re.search(r'<div[^>]*id="dic_area"[^>]*>(.*?)</div>\s*</div>', text, re.S)
body=m.group(1) if m else text
body=re.sub(r'<br\s*/?>','\n',body)
body=re.sub(r'<script.*?</script>|<style.*?</style>','',body, flags=re.S)
plain=html.unescape(re.sub(r'<[^>]+>',' ',body))
plain=re.sub(r'\s+',' ',plain).strip()
print(plain[:4000])
PY
```

## Notes

- The runtime may not have `bs4`; prefer the regex/html fallback unless BeautifulSoup is already available.
- Metadata commonly used in notes:
  - `og:title` for title
  - `og:article:author` often appears as `<매체> | 네이버`
  - `data-date-time` for publication date
  - `#dic_area` / `<article id="dic_area">` for the article body
- For quoted-text requests, search the extracted `plain` body for the exact phrase before adding the quote to the wiki/devnote page.
- Keep the original Naver URL if the user provided it, unless they explicitly ask for a canonical non-Naver source.
