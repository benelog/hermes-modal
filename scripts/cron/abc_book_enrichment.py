#!/usr/bin/env python3
"""Prepare book/URL enrichment context for the ABC weekly summary cron.

The script fetches recent KakaoTalk collector data, filters the
ABC(아카라카북클럽) room for the previous Monday-Sunday window in Asia/Seoul,
and emits compact JSON that the LLM can use before writing the final summary.

It intentionally does not fabricate book titles/authors: each enrichment result
records the source that produced it (message text, URL metadata, web search, or
OCR when an image payload is actually available).
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
import re
import ssl
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOM = "ABC(아카라카북클럽)"
URL_RE = re.compile(r"https?://\S+")
BOOK_HINT_RE = re.compile(
    r"책|도서|읽|추천|소설|작가|저자|출판|서점|문학|독서|알라딘|교보|yes24|예스24|"
    r"《|『|에세이|시집|만화|서평|대본집|희곡|자서전|오디오북|구매",
    re.I,
)
AUTHOR_RE = re.compile(r"([가-힣A-Za-z][가-힣A-Za-z·.\s]{1,30}?)(?:\s*작가(?:님)?|\s*저자(?:님)?|\s*셰프|\s*쉐프)")
TITLE_RE = re.compile(r"[《『‘'\"]([^《》『』‘’'\"\n]{2,80})[》』’'\"]")
IMAGE_KEYS = (
    "image_url", "image_urls", "photo_url", "photo_urls", "attachment_url", "attachment_urls",
    "attachments", "media", "files", "image_path", "photo_path",
)


def kst_today() -> dt.date:
    return dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).date()


def previous_monday_sunday(today: dt.date | None = None) -> tuple[str, str]:
    today = today or kst_today()
    # Monday is 0. On Monday, previous Monday is seven days ago.
    this_monday = today - dt.timedelta(days=today.weekday())
    start = this_monday - dt.timedelta(days=7)
    end = this_monday - dt.timedelta(days=1)
    return start.isoformat(), end.isoformat()


def fetch_kakao(since: str) -> dict[str, Any]:
    helper = Path("/root/.hermes/scripts/kakao_fetch.py")
    if not helper.exists():
        helper = Path(__file__).with_name("kakao_fetch.py")
    proc = subprocess.run(
        [sys.executable, str(helper), "--since", since],
        check=False,
        text=True,
        capture_output=True,
        timeout=60,
    )
    if proc.returncode != 0:
        return {"ok": False, "error": "kakao_fetch failed", "stderr": proc.stderr, "stdout": proc.stdout[:1000]}
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        return {"ok": False, "error": f"kakao_fetch returned non-JSON: {exc}", "stdout": proc.stdout[:1000]}


def clean_text(s: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(s or "")).strip()


def strip_tags(s: str) -> str:
    return clean_text(re.sub(r"<[^>]+>", " ", s or ""))


def fetch_html(url: str, timeout: int = 12) -> tuple[str, str, str]:
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Hermes ABC summary)"})
    with urllib.request.urlopen(req, timeout=timeout, context=ssl._create_unverified_context()) as resp:
        final_url = resp.geturl()
        ctype = resp.headers.get("content-type", "")
        raw = resp.read(500_000)
    encoding = "utf-8"
    m = re.search(r"charset=([^;\s]+)", ctype, re.I)
    if m:
        encoding = m.group(1)
    return final_url, raw.decode(encoding, "ignore"), ctype


def meta_content(text: str, names: list[str]) -> str:
    for name in names:
        patterns = [
            rf'<meta[^>]+(?:property|name)=["\']{re.escape(name)}["\'][^>]+content=["\']([^"\']+)',
            rf'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\']{re.escape(name)}["\']',
        ]
        for pat in patterns:
            m = re.search(pat, text, re.I | re.S)
            if m:
                return clean_text(m.group(1))
    return ""


def page_title(text: str) -> str:
    title = meta_content(text, ["og:title", "twitter:title"])
    if title:
        return title
    m = re.search(r"<title[^>]*>(.*?)</title>", text, re.I | re.S)
    return strip_tags(m.group(1)) if m else ""


def yes24_book(text: str) -> dict[str, str]:
    title = meta_content(text, ["og:title", "twitter:title"])
    # YES24 titles are often "title | author | publisher - 예스24".
    out: dict[str, str] = {}
    if title:
        parts = [p.strip() for p in re.split(r"\s*\|\s*", title)]
        if parts:
            out["title"] = re.sub(r"\s*-\s*예스24$", "", parts[0]).strip()
        if len(parts) >= 2:
            out["author"] = parts[1].replace(" 저", "").strip()
    m = re.search(r'<span[^>]+class=["\']gd_auth["\'][^>]*>(.*?)</span>', text, re.I | re.S)
    if m and not out.get("author"):
        out["author"] = strip_tags(m.group(1)).replace(" 저", "").strip()
    return {k: v for k, v in out.items() if v}


def aladin_book(text: str) -> dict[str, str]:
    title = meta_content(text, ["og:title", "twitter:title"])
    out: dict[str, str] = {}
    if title:
        title = re.sub(r"\s*-\s*알라딘.*$", "", title).strip()
        out["title"] = title
    # Common Aladin snippets include author lines around Ere_prod_mconts_box.
    m = re.search(r"지은이\s*[:：]?\s*</?[^>]*>\s*([^<\n]+)", text, re.I)
    if m:
        out["author"] = clean_text(m.group(1))
    return {k: v for k, v in out.items() if v}


def kyobo_book(text: str) -> dict[str, str]:
    title = meta_content(text, ["og:title", "twitter:title"])
    out: dict[str, str] = {}
    if title:
        title = re.sub(r"\s*\|\s*교보문고.*$", "", title).strip()
        out["title"] = title
    m = re.search(r'"author"\s*:\s*"([^"]+)"', text)
    if m:
        out["author"] = clean_text(m.group(1))
    return {k: v for k, v in out.items() if v}


def normalize_yes24_mobile(url: str) -> str:
    m = re.search(r"/goods/detail/(\d+)", url)
    if m:
        return f"https://m.yes24.com/goods/detail/{m.group(1)}"
    return url


def parse_book_from_url(url: str) -> dict[str, Any]:
    result: dict[str, Any] = {"url": url, "source": "url"}
    try:
        final_url, text, ctype = fetch_html(url)
    except Exception as exc:  # noqa: BLE001 - keep summary moving under rate limits
        result.update({"error": repr(exc)})
        return result
    result["final_url"] = final_url
    result["content_type"] = ctype
    title = page_title(text)
    if title:
        result["page_title"] = title
    host = urllib.parse.urlparse(final_url).netloc.lower()
    book: dict[str, str] = {}
    if "yes24" in host:
        book = yes24_book(text)
    elif "aladin" in host:
        book = aladin_book(text)
    elif "kyobobook" in host or "교보" in title:
        book = kyobo_book(text)
    # Generic bookish metadata fallback.
    if not book.get("author"):
        author = meta_content(text, ["book:author", "article:author", "author"])
        if author:
            book["author"] = author
    if not book.get("title") and title and (BOOK_HINT_RE.search(title) or any(site in host for site in ["yes24", "aladin", "kyobobook"])):
        book["title"] = re.sub(r"\s*[-|]\s*(예스24|알라딘|교보문고).*$", "", title).strip()
    if book:
        if "yes24" in host:
            book["purchase_link"] = normalize_yes24_mobile(final_url)
        result["book"] = book
    return result


def extract_message_clues(text: str) -> dict[str, Any]:
    titles = [clean_text(x) for x in TITLE_RE.findall(text or "")]
    authors = [clean_text(x) for x in AUTHOR_RE.findall(text or "")]
    return {"titles": list(dict.fromkeys(titles)), "authors": list(dict.fromkeys(authors))}


def ddg_search(query: str, limit: int = 5) -> list[dict[str, str]]:
    url = "https://duckduckgo.com/html/?q=" + urllib.parse.quote(query)
    try:
        _, text, _ = fetch_html(url, timeout=12)
    except Exception:
        return []
    results: list[dict[str, str]] = []
    for m in re.finditer(r'<a[^>]+class=["\']result__a["\'][^>]+href=["\'](.*?)["\'][^>]*>(.*?)</a>', text, re.I | re.S):
        href = html.unescape(m.group(1))
        parsed = urllib.parse.urlparse(href)
        qs = urllib.parse.parse_qs(parsed.query)
        if "uddg" in qs:
            href = qs["uddg"][0]
        results.append({"title": strip_tags(m.group(2)), "url": href})
        if len(results) >= limit:
            break
    return results


def yes24_purchase_search(title: str = "", author: str = "", context: str = "") -> list[dict[str, str]]:
    query_parts = [p for p in [title, author] if p]
    if not query_parts:
        # Keep the fallback short; long chat text hurts search precision.
        query_parts = re.findall(r"[가-힣A-Za-z0-9]{2,}", context)[:6]
    if not query_parts:
        return []
    query = " ".join(query_parts) + " site:yes24.com/goods/detail"
    out: list[dict[str, str]] = []
    seen: set[str] = set()
    for r in ddg_search(query, limit=6):
        url = r.get("url", "")
        if "yes24.com" not in url or "/goods/detail/" not in url:
            continue
        link = normalize_yes24_mobile(url)
        if link in seen:
            continue
        seen.add(link)
        out.append({"title": r.get("title", ""), "url": link, "query": query})
    return out[:3]


def search_book_from_clues(authors: list[str], titles: list[str], context: str) -> list[dict[str, Any]]:
    queries: list[str] = []
    for title in titles[:3]:
        queries.append(f'{title} 책 저자')
    for author in authors[:3]:
        # For author-recognized/title-missing cases, include context words but keep query short.
        words = " ".join(re.findall(r"[가-힣A-Za-z0-9]{2,}", context)[:8])
        queries.append(f'{author} 책 {words}')
    out: list[dict[str, Any]] = []
    seen: set[str] = set()
    for q in queries:
        for r in ddg_search(q, limit=3):
            key = r.get("url", "") or r.get("title", "")
            if key in seen:
                continue
            seen.add(key)
            r["query"] = q
            out.append(r)
    return out[:8]


def iter_image_refs(message: dict[str, Any]) -> list[str]:
    refs: list[str] = []
    for key in IMAGE_KEYS:
        val = message.get(key)
        if not val:
            continue
        if isinstance(val, str):
            refs.append(val)
        elif isinstance(val, list):
            for item in val:
                if isinstance(item, str):
                    refs.append(item)
                elif isinstance(item, dict):
                    for k in ("url", "path", "image_url", "file_url", "local_path"):
                        if item.get(k):
                            refs.append(str(item[k]))
        elif isinstance(val, dict):
            for k in ("url", "path", "image_url", "file_url", "local_path"):
                if val.get(k):
                    refs.append(str(val[k]))
    return list(dict.fromkeys(refs))


def ocr_image(ref: str) -> dict[str, str]:
    # Supports local paths or direct image URLs when tesseract is installed.
    tesseract = shutil_which("tesseract")
    if not tesseract:
        return {"image": ref, "error": "tesseract not installed"}
    tmp_path: Path | None = None
    src = ref
    try:
        if ref.startswith("http://") or ref.startswith("https://"):
            suffix = Path(urllib.parse.urlparse(ref).path).suffix or ".img"
            fd, name = tempfile.mkstemp(suffix=suffix)
            os.close(fd)
            tmp_path = Path(name)
            urllib.request.urlretrieve(ref, tmp_path)
            src = str(tmp_path)
        proc = subprocess.run([tesseract, src, "stdout", "-l", "kor+eng"], text=True, capture_output=True, timeout=30)
        if proc.returncode != 0:
            return {"image": ref, "error": proc.stderr[:300]}
        return {"image": ref, "text": clean_text(proc.stdout)[:1000]}
    except Exception as exc:  # noqa: BLE001
        return {"image": ref, "error": repr(exc)}
    finally:
        if tmp_path:
            try:
                tmp_path.unlink()
            except OSError:
                pass


def shutil_which(cmd: str) -> str | None:
    for path in os.environ.get("PATH", "").split(os.pathsep):
        candidate = Path(path) / cmd
        if candidate.exists() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def summarize(messages: list[dict[str, Any]], start: str, end: str) -> dict[str, Any]:
    urls: list[dict[str, Any]] = []
    candidates: list[dict[str, Any]] = []
    seen_urls: set[str] = set()
    for idx, msg in enumerate(messages):
        text = msg.get("text") or ""
        msg_urls = []
        for url in URL_RE.findall(text):
            url = url.rstrip(").,]。")
            if url not in seen_urls:
                seen_urls.add(url)
                enriched = parse_book_from_url(url)
                enriched.update({"message_index": idx, "client_time": msg.get("client_time"), "sender": msg.get("sender"), "context": clean_text(text)[:500]})
                urls.append(enriched)
            msg_urls.append(url)
        if BOOK_HINT_RE.search(text):
            clues = extract_message_clues(text)
            item: dict[str, Any] = {
                "message_index": idx,
                "client_time": msg.get("client_time"),
                "sender": msg.get("sender"),
                "context": clean_text(text)[:800],
                "message_titles": clues["titles"],
                "message_authors": clues["authors"],
                "urls": msg_urls,
            }
            if (clues["authors"] and not clues["titles"]) or (clues["titles"] and not clues["authors"]):
                item["web_search_results"] = search_book_from_clues(clues["authors"], clues["titles"], text)
            if clues["titles"] or clues["authors"]:
                purchase_candidates: list[dict[str, str]] = []
                for title in (clues["titles"] or [""])[:3]:
                    for author in (clues["authors"] or [""])[:3]:
                        purchase_candidates.extend(yes24_purchase_search(title=title, author=author, context=text))
                # Deduplicate while preserving search order.
                deduped: list[dict[str, str]] = []
                seen_purchase: set[str] = set()
                for candidate in purchase_candidates:
                    url = candidate.get("url", "")
                    if url and url not in seen_purchase:
                        seen_purchase.add(url)
                        deduped.append(candidate)
                if deduped:
                    item["yes24_purchase_candidates"] = deduped[:3]
            image_refs = iter_image_refs(msg)
            if image_refs:
                item["image_ocr"] = [ocr_image(ref) for ref in image_refs[:3]]
            elif "사진" in text or "이미지" in text or "[사진" in text:
                item["image_ocr"] = [{"error": "collector text indicates an image/photo, but no image URL/path field was available"}]
            candidates.append(item)
    return {
        "ok": True,
        "room": ROOM,
        "start_date": start,
        "end_date": end,
        "message_count": len(messages),
        "unique_url_count": len(urls),
        "urls": urls,
        "book_candidates": candidates,
        "resolution_order": [
            "1) recognize author/title clues from message text",
            "2) visit and parse shared URLs",
            "3) run internet/bookstore search for missing title/author",
            "4) OCR associated image attachments when image URL/path exists",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input-json", help="Use an existing kakao_fetch JSON file instead of fetching")
    parser.add_argument("--since", default="9day")
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--room", default=ROOM)
    parser.add_argument("--max-output-chars", type=int, default=45000)
    args = parser.parse_args()

    start, end = (args.start, args.end) if args.start and args.end else previous_monday_sunday()
    if args.input_json:
        data = json.loads(Path(args.input_json).read_text(encoding="utf-8"))
    else:
        data = fetch_kakao(args.since)
    if not data.get("ok", False):
        print(json.dumps(data, ensure_ascii=False))
        return 1
    messages = [
        m for m in data.get("messages", [])
        if m.get("room") == args.room and start <= (m.get("client_time", "")[:10]) <= end
    ]
    out = summarize(messages, start, end)
    text = json.dumps(out, ensure_ascii=False, indent=2)
    if len(text) > args.max_output_chars:
        out["truncated"] = True
        # Keep JSON valid under the output cap: trim contexts/searches first, then
        # reduce candidate count while preserving URL enrichment.
        for item in out["book_candidates"]:
            item["context"] = item.get("context", "")[:280]
            if "web_search_results" in item:
                item["web_search_results"] = item["web_search_results"][:2]
            if "image_ocr" in item:
                for ocr in item["image_ocr"]:
                    if "text" in ocr:
                        ocr["text"] = ocr["text"][:300]
        while len(json.dumps(out, ensure_ascii=False, indent=2)) > args.max_output_chars and len(out["book_candidates"]) > 20:
            out["book_candidates"] = out["book_candidates"][: max(20, len(out["book_candidates"]) - 10)]
        text = json.dumps(out, ensure_ascii=False, indent=2)
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
