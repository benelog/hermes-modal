"""온라인 서점(YES24·교보문고·알라딘) 베스트셀러 순위 수집기.

매일 아침 Modal 스케줄러(`modal_app.py::book_rank_tick`)가 호출한다.
- 각 서점 상품 페이지에서 "부문 + 순위"를 읽는다.
- 날짜별 이력을 JSON 파일(Modal Volume)에 쌓는다.
- 전날 대비 순위 변동과 집계 부문 변경을 감지해 Telegram 메시지로 만든다.
- 날짜별 순위 표 HTML 페이지를 렌더링한다(`book_rank_page`).

외부 의존성 없이 표준 라이브러리만 사용한다.
"""
from __future__ import annotations

import html
import json
import re
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable

BOOK_TITLE = "AI 에이전트와 스프링 배치를 활용한 배치 엔지니어링"
BOOK_SHORT_TITLE = "스프링 배치 with AI"
PUBLISHER_URL = "https://wikibook.co.kr/spring-batch-with-ai/"

# (키, 표시 이름, 상품 URL) — 표/메시지의 열 순서도 이 순서를 따른다.
STORES = [
    ("yes24", "YES24", "https://www.yes24.com/product/goods/199971666"),
    ("kyobo", "교보문고", "https://product.kyobobook.co.kr/detail/S000221888579"),
    ("aladin", "알라딘", "https://www.aladin.co.kr/shop/wproduct.aspx?ItemId=406178449"),
]
STORE_NAMES = {key: name for key, name, _ in STORES}

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
)
# 교보문고는 UA만 브라우저여도 빈 응답을 준다. 일반 브라우저의 페이지 이동 헤더를
# 함께 보내야 HTML이 온다(2026-11-18 확인).
BROWSER_HEADERS = {
    "User-Agent": USER_AGENT,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Upgrade-Insecure-Requests": "1",
}

# "IT 모바일 84위", "컴퓨터/모바일 주간 85위", "컴퓨터/IT 117위"
_RANK_RE = re.compile(r"^\s*(?P<category>.+?)\s+(?P<rank>\d{1,5})\s*위\s*$")


@dataclass
class StoreRank:
    store: str
    category: str | None = None  # 집계 부문(예: "IT 모바일")
    rank: int | None = None  # None이면 순위권 밖 또는 수집 실패
    note: str | None = None  # 부가 정보(예: "IT 모바일 top100 1주")
    error: str | None = None  # 수집 실패 사유
    manual: bool = False  # 메신저로 수동 입력한 값

    def label(self) -> str:
        if self.error:
            return "수집 실패"
        if self.rank is None:
            return "순위 없음"
        return f"{self.category} {self.rank}위" if self.category else f"{self.rank}위"


# ---------------------------------------------------------------- 공통 유틸


def _text(fragment: str) -> str:
    """HTML 조각에서 태그를 지우고 공백을 정리한 텍스트."""
    no_tags = re.sub(r"<[^>]+>", " ", fragment)
    return re.sub(r"\s+", " ", html.unescape(no_tags)).strip()


def parse_rank_text(text: str) -> tuple[str | None, int | None]:
    """'IT 모바일 84위' → ('IT 모바일', 84). 형식이 다르면 (None, None)."""
    m = _RANK_RE.match(_text(text))
    if not m:
        return None, None
    return m.group("category").strip(), int(m.group("rank"))


def http_get(url: str, referer: str | None = None, timeout: int = 20) -> str:
    headers = dict(BROWSER_HEADERS)
    if referer:
        headers["Referer"] = referer
        headers["Sec-Fetch-Site"] = "same-origin"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read()
        charset = resp.headers.get_content_charset() or "utf-8"
    return body.decode(charset, errors="replace")


# ---------------------------------------------------------------- 서점별 파서


def parse_yes24_module_url(product_html: str, product_url: str) -> str | None:
    """상품 페이지가 비동기로 부르는 베스트셀러 순위 모듈 URL을 찾는다."""
    m = re.search(r"""(/Product/addModules/BestSellerRank_Book/[^"'\s]+)""", product_html)
    if not m:
        return None
    return urllib.parse.urljoin(product_url, html.unescape(m.group(1)))


def parse_yes24_rank(module_html: str) -> StoreRank:
    """<span class="gd_best"> … <a>IT 모바일 84위</a> | IT 모바일 top100 1주 …"""
    block = re.search(r"<dd[^>]*>(.*?)</dd>", module_html, re.S)
    if not block:
        return StoreRank("yes24")  # 모듈이 비어 있으면 순위권 밖
    anchor = re.search(r"<a[^>]*>(.*?)</a>", block.group(1), re.S)
    category, rank = parse_rank_text(anchor.group(1)) if anchor else (None, None)
    rest = block.group(1)[anchor.end():] if anchor else block.group(1)
    note = _text(rest).lstrip("|").strip() or None
    return StoreRank("yes24", category, rank, note)


def parse_aladin_rank(product_html: str) -> StoreRank:
    """<div id="..._pnlRanking"> <a href="wbest.aspx?...">컴퓨터/모바일 주간 85위</a>"""
    i = product_html.find("wa_Top_Ranking_pnlRanking")
    if i < 0:
        return StoreRank("aladin")
    region = product_html[i : i + 3000]
    for anchor in re.finditer(r"<a[^>]*wbest[^>]*>(.*?)</a>", region, re.S):
        category, rank = parse_rank_text(anchor.group(1))
        if rank is not None:
            # "컴퓨터/모바일 주간" → 부문 "컴퓨터/모바일", 집계 기간 "주간"
            note = None
            m = re.match(r"(.+?)\s+(일간|주간|월간)$", category or "")
            if m:
                category, note = m.group(1), m.group(2)
            return StoreRank("aladin", category, rank, note)
    return StoreRank("aladin")


def parse_kyobo_rank(product_html: str) -> StoreRank:
    """<a href="https://store.kyobobook.co.kr/bestseller/online/weekly/domestic/33">…컴퓨터/IT 117위</a>"""
    for anchor in re.finditer(
        r"""<a[^>]*href=["'][^"']*/bestseller/([^"']*)["'][^>]*>(.*?)</a>""", product_html, re.S
    ):
        category, rank = parse_rank_text(anchor.group(2))
        if rank is not None:
            period = anchor.group(1).split("/")
            note = {"daily": "일간", "weekly": "주간", "monthly": "월간"}.get(period[1]) if len(period) > 1 else None
            # 앵커 안의 "주간베스트" 라벨은 부문이 아니라 집계 기간이다.
            m = re.match(r"(일간|주간|월간)\s*베스트\s+(.+)", category or "")
            if m:
                note, category = m.group(1), m.group(2)
            return StoreRank("kyobo", category, rank, note)
    return StoreRank("kyobo")


# ---------------------------------------------------------------- 수집


def fetch_yes24(url: str, get: Callable[..., str] = http_get) -> StoreRank:
    page = get(url)
    module_url = parse_yes24_module_url(page, url)
    if not module_url:
        return StoreRank("yes24", error="순위 모듈 URL을 찾지 못함")
    return parse_yes24_rank(get(module_url, referer=url))


def fetch_aladin(url: str, get: Callable[..., str] = http_get) -> StoreRank:
    return parse_aladin_rank(get(url))


def fetch_kyobo(url: str, get: Callable[..., str] = http_get) -> StoreRank:
    page = get(url)
    if not page.strip():
        # 교보는 CloudFront가 비브라우저 요청에 빈 응답을 돌려줄 때가 있다.
        return StoreRank("kyobo", error="빈 응답(접근 차단 추정)")
    return parse_kyobo_rank(page)


FETCHERS = {"yes24": fetch_yes24, "kyobo": fetch_kyobo, "aladin": fetch_aladin}


def collect_all(get: Callable[..., str] = http_get) -> dict[str, StoreRank]:
    results: dict[str, StoreRank] = {}
    for key, _name, url in STORES:
        try:
            results[key] = FETCHERS[key](url, get)
        except Exception as exc:  # 한 서점 실패가 전체를 막지 않도록
            results[key] = StoreRank(key, error=f"{type(exc).__name__}: {exc}"[:200])
    return results


# ---------------------------------------------------------------- 이력 저장


def load_history(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8")).get("records", [])


def save_history(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(
        json.dumps({"records": records}, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    tmp.replace(path)


def _entry(result: StoreRank) -> dict:
    entry = {f: v for f, v in asdict(result).items() if f != "store"}
    if not entry["manual"]:
        del entry["manual"]
    return entry


def upsert_record(records: list[dict], date: str, results: dict[str, StoreRank], fetched_at: str) -> list[dict]:
    """같은 날짜를 다시 수집하면 서점별로 덮어쓴다. 날짜 오름차순 유지.

    새 결과가 수집 실패이고 기존 값이 정상(예: 메신저 수동 입력)이면 기존 값을 지킨다.
    결과에 없는 서점의 기존 값도 그대로 둔다.
    """
    existing = next((r for r in records if r["date"] == date), None)
    stores = dict(existing["stores"]) if existing else {}
    for key, result in results.items():
        old = stores.get(key)
        if result.error and old and not old.get("error"):
            continue
        stores[key] = _entry(result)
    record = {"date": date, "fetched_at": fetched_at, "stores": stores}
    others = [r for r in records if r["date"] != date]
    return sorted(others + [record], key=lambda r: r["date"])


_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def parse_manual_input(text: str, default_category: str | None) -> tuple[str | None, StoreRank]:
    """메신저 입력을 해석한다. 반환: (날짜 또는 None=오늘, StoreRank).

    예) "117" / "117위" / "컴퓨터/IT 117위" / "2026-11-18 컴퓨터/IT 117" / "없음"
    부문을 생략하면 default_category(직전 기록의 부문)를 쓴다.
    """
    tokens = text.split()
    date = None
    if tokens and _DATE_RE.match(tokens[0]):
        date = tokens.pop(0)
    rest = " ".join(tokens).strip()
    if rest in ("없음", "순위없음", "순위 없음", "-"):
        return date, StoreRank("kyobo", default_category, None, "주간", manual=True)
    m = re.match(r"^(?:(?P<category>.+?)\s+)?(?P<rank>\d{1,5})\s*위?$", rest)
    if not m:
        raise ValueError(
            "형식을 모르겠어요. 예: /kyobo 117 · /kyobo 컴퓨터/IT 117위 · "
            "/kyobo 2026-11-18 컴퓨터/IT 117 · /kyobo 없음"
        )
    category = (m.group("category") or "").strip() or default_category
    if not category:
        raise ValueError("이전 교보 부문 기록이 없어요. 부문도 함께 적어 주세요. 예: /kyobo 컴퓨터/IT 117")
    return date, StoreRank("kyobo", category, int(m.group("rank")), "주간", manual=True)


def last_category(records: list[dict], store: str) -> str | None:
    for rec in reversed(records):
        entry = rec.get("stores", {}).get(store) or {}
        if entry.get("category") and not entry.get("error"):
            return entry["category"]
    return None


def _previous_ok(records: list[dict], date: str, store: str) -> dict | None:
    """date 이전의 가장 최근 정상 수집 결과(실패 기록은 건너뛴다)."""
    for rec in reversed(records):
        if rec["date"] >= date:
            continue
        entry = rec.get("stores", {}).get(store)
        if entry and not entry.get("error"):
            return entry
    return None


def annotate(records: list[dict]) -> list[dict]:
    """각 기록/서점에 전날 대비 변동(delta)과 부문 변경(category_changed_from)을 붙인다."""
    out = []
    for rec in records:
        stores = {}
        for key, entry in rec.get("stores", {}).items():
            entry = dict(entry)
            prev = _previous_ok(records, rec["date"], key)
            if prev and not entry.get("error"):
                if (
                    prev.get("category")
                    and entry.get("category")
                    and prev["category"] != entry["category"]
                ):
                    entry["category_changed_from"] = prev["category"]
                elif prev.get("rank") is not None and entry.get("rank") is not None:
                    # 부문이 같을 때만 순위 비교가 의미 있다. 양수 = 상승.
                    entry["delta"] = prev["rank"] - entry["rank"]
                if prev.get("rank") is not None and entry.get("rank") is None:
                    entry["dropped_out"] = True
            stores[key] = entry
        out.append({**rec, "stores": stores})
    return out


# ---------------------------------------------------------------- 출력


def _delta_text(entry: dict) -> str:
    d = entry.get("delta")
    if d is None:
        return ""
    if d > 0:
        return f"▲{d}"
    if d < 0:
        return f"▼{-d}"
    return "-"


def _entry_label(entry: dict) -> str:
    if entry.get("error"):
        return "수집 실패"
    if entry.get("rank") is None:
        return "순위 없음"
    parts = [entry.get("category") or ""]
    if entry.get("note") in ("일간", "주간", "월간"):
        parts.append(entry["note"])
    parts.append(f"{entry['rank']}위")
    return " ".join(p for p in parts if p)


def format_telegram_message(records: list[dict], date: str, page_url: str | None = None) -> str:
    annotated = {r["date"]: r for r in annotate(records)}
    rec = annotated[date]
    lines = [f"📚 {BOOK_SHORT_TITLE} 서점 순위 ({date})", ""]
    changes = []
    for key, name, _url in STORES:
        entry = rec["stores"].get(key, {"error": "기록 없음"})
        line = f"• {name}: {_entry_label(entry)}"
        delta = _delta_text(entry)
        if delta:
            line += f" ({delta})"
        if entry.get("dropped_out"):
            line += " (순위권 이탈)"
        if entry.get("manual"):
            line += " (수동)"
        if entry.get("error"):
            line += f" — {entry['error']}"
            if key == "kyobo":
                line += "\n  ↳ 확인 후 /kyobo 117 처럼 보내면 기록됩니다"
        lines.append(line)
        if entry.get("category_changed_from"):
            changes.append(f"• {name}: {entry['category_changed_from']} → {entry.get('category')}")
    if changes:
        lines += ["", "⚠️ 집계 부문 변경"] + changes
    if page_url:
        lines += ["", f"📊 날짜별 순위표: {page_url}"]
    return "\n".join(lines)


def render_html(records: list[dict]) -> str:
    rows = []
    for rec in reversed(annotate(records)):  # 최신 날짜가 위
        cells = [f'<th scope="row">{html.escape(rec["date"])}</th>']
        for key, _name, _url in STORES:
            entry = rec["stores"].get(key)
            if not entry:
                cells.append('<td class="empty">-</td>')
                continue
            if entry.get("error"):
                cells.append(f'<td class="fail" title="{html.escape(entry["error"])}">수집 실패</td>')
                continue
            if entry.get("rank") is None:
                cells.append('<td class="empty">순위 없음</td>')
                continue
            cat = html.escape(entry.get("category") or "")
            period = entry.get("note") if entry.get("note") in ("일간", "주간", "월간") else None
            delta = _delta_text(entry)
            delta_cls = "up" if (entry.get("delta") or 0) > 0 else "down" if (entry.get("delta") or 0) < 0 else "same"
            changed = entry.get("category_changed_from")
            cls = ' class="changed"' if changed else ""
            inner = f'<span class="rank">{entry["rank"]}위</span>'
            if delta:
                inner += f' <span class="delta {delta_cls}">{delta}</span>'
            manual = " · 수동" if entry.get("manual") else ""
            inner += f'<div class="cat">{cat}{" · " + period if period else ""}{manual}</div>'
            if changed:
                inner += f'<div class="chg">부문 변경: {html.escape(changed)} → {cat}</div>'
            cells.append(f"<td{cls}>{inner}</td>")
        rows.append("<tr>" + "".join(cells) + "</tr>")

    head = "".join(
        f'<th scope="col"><a href="{html.escape(url)}">{html.escape(name)}</a></th>'
        for _key, name, url in STORES
    )
    body = "\n".join(rows) or f'<tr><td colspan="{len(STORES) + 1}" class="empty">아직 수집된 기록이 없습니다.</td></tr>'
    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>서점 순위 추이</title>
<style>
:root {{ --bg:#fff; --fg:#1d1d1f; --muted:#6e6e73; --line:#e5e5ea; --up:#c0392b; --down:#2463eb; --warn-bg:#fff4d6; --warn:#8a5a00; --head:#f5f5f7; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#1c1c1e; --fg:#f2f2f7; --muted:#a1a1a6; --line:#3a3a3c; --up:#ff6b5e; --down:#6ea8ff; --warn-bg:#3d3214; --warn:#ffd479; --head:#2c2c2e; }} }}
body {{ margin:0; padding:24px 16px; background:var(--bg); color:var(--fg); font:15px/1.5 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Noto Sans KR",sans-serif; }}
main {{ max-width:860px; margin:0 auto; }}
h1 {{ font-size:20px; margin:0 0 4px; }}
p.sub {{ color:var(--muted); margin:0 0 20px; font-size:13px; }}
a {{ color:inherit; }}
.wrap {{ overflow-x:auto; }}
table {{ border-collapse:collapse; width:100%; min-width:480px; }}
th, td {{ border-bottom:1px solid var(--line); padding:8px 10px; text-align:left; vertical-align:top; }}
thead th {{ background:var(--head); font-size:13px; position:sticky; top:0; }}
tbody th {{ font-weight:500; white-space:nowrap; font-variant-numeric:tabular-nums; }}
.rank {{ font-weight:700; font-variant-numeric:tabular-nums; }}
.cat {{ color:var(--muted); font-size:12px; }}
.delta {{ font-size:12px; font-variant-numeric:tabular-nums; }}
.up {{ color:var(--up); }} .down {{ color:var(--down); }} .same {{ color:var(--muted); }}
td.changed {{ background:var(--warn-bg); }}
.chg {{ color:var(--warn); font-size:12px; font-weight:600; }}
td.fail, td.empty {{ color:var(--muted); font-size:13px; }}
</style></head>
<body><main>
<h1>{html.escape(BOOK_TITLE)}</h1>
<p class="sub">매일 07:00(KST) 수집 · 서점별 베스트셀러 부문과 순위 · ▲/▼는 직전 수집 대비(같은 부문일 때만) · 노란 칸은 집계 부문 변경 · <a href="{PUBLISHER_URL}">도서 소개</a></p>
<div class="wrap"><table>
<thead><tr><th scope="col">날짜</th>{head}</tr></thead>
<tbody>
{body}
</tbody></table></div>
</main></body></html>"""


# ---------------------------------------------------------------- Telegram


def send_telegram(token: str, chat_id: str, text: str) -> None:
    data = urllib.parse.urlencode(
        {"chat_id": chat_id, "text": text, "disable_web_page_preview": "true"}
    ).encode()
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/sendMessage", data=data)
    with urllib.request.urlopen(req, timeout=20) as resp:
        payload = json.loads(resp.read())
    if not payload.get("ok"):
        raise RuntimeError(f"Telegram 전송 실패: {payload}")
