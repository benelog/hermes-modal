"""수집된 카카오톡 메시지를 브라우저에서 훑어보는 HTML 조회 화면 렌더러.

`kakao-viewer` 엔드포인트가 Dict 레코드를 넘기면 완성된 HTML 문자열을 돌려준다.
서버에서 바로 그리므로 별도 API 호출(CORS)이 없고, 토큰은 필터 폼의 hidden 필드로만 유지한다.
"""

from __future__ import annotations

import html
from datetime import datetime

from .collector_core import KST, effective_sent_at, normalize_sent_time, select_messages

SINCE_CHOICES = [("1day", "1일"), ("3day", "3일"), ("7day", "7일"), ("14day", "14일")]
_WEEKDAYS = "월화수목금토일"


def _esc(value) -> str:
    return html.escape(str(value or ""), quote=True)


def _date_label(day: datetime) -> str:
    return f"{day:%Y-%m-%d} ({_WEEKDAYS[day.weekday()]})"


def _received_kst(rec: dict) -> str:
    try:
        return datetime.fromisoformat(rec.get("received_at", "")).astimezone(KST).strftime("%m/%d %H:%M")
    except (ValueError, TypeError):
        return ""


def _time_cell(rec: dict, sent: datetime) -> str:
    """발신 시각 표시. 화면에서 읽은 시각이 없으면 '~'(수신 시각 기준 추정)을 붙인다."""
    if normalize_sent_time(rec.get("sent_time")):
        return f"{sent:%H:%M}"
    return f"~{sent:%H:%M}"


def day_stats(messages: list[dict]) -> list[dict]:
    """발신일(KST)별 건수와 날짜/시각 수집 여부 집계. 최신 날짜 먼저."""
    by_day: dict[str, dict] = {}
    for rec in messages:
        day = effective_sent_at(rec).astimezone(KST)
        key = f"{day:%Y-%m-%d}"
        row = by_day.setdefault(key, {"date": _date_label(day), "total": 0, "dated": 0, "timed": 0, "rooms": {}})
        row["total"] += 1
        if (rec.get("client_time") or "").strip():
            row["dated"] += 1
        if normalize_sent_time(rec.get("sent_time")):
            row["timed"] += 1
        room = rec.get("room") or ""
        row["rooms"][room] = row["rooms"].get(room, 0) + 1
    return [by_day[k] for k in sorted(by_day, reverse=True)]


def render_viewer(records, room: str, since: str, now: datetime, token: str) -> str:
    rooms = sorted({r.get("room") or "" for r in records if r.get("room")})
    if room not in rooms:
        room = ""
    if since not in {value for value, _ in SINCE_CHOICES}:
        since = "3day"
    messages = select_messages(records, room or None, since, now)
    stats = day_stats(messages)

    room_options = ['<option value="">전체 방</option>'] + [
        f'<option value="{_esc(r)}"{" selected" if r == room else ""}>{_esc(r)}</option>' for r in rooms
    ]
    since_options = [
        f'<option value="{v}"{" selected" if v == since else ""}>최근 {label}</option>' for v, label in SINCE_CHOICES
    ]

    stat_rows = []
    for row in stats:
        per_room = " · ".join(f"{_esc(r)} {n}" for r, n in sorted(row["rooms"].items())) if not room else ""
        stat_rows.append(
            f'<tr><td>{_esc(row["date"])}</td><td class="num">{row["total"]}</td>'
            f'<td class="num">{row["dated"]}</td><td class="num">{row["timed"]}</td>'
            f'<td class="muted">{per_room}</td></tr>'
        )
    if not stat_rows:
        stat_rows.append('<tr><td colspan="5" class="muted">이 기간에 수집된 메시지가 없어요.</td></tr>')

    # 최신 날짜가 위, 하루 안에서는 대화 순서(오래된 것 위)로 보여준다.
    groups: dict[str, list[str]] = {}
    for rec in messages:
        sent = effective_sent_at(rec).astimezone(KST)
        label = _date_label(sent)
        flags = []
        if not (rec.get("client_time") or "").strip():
            flags.append('<span class="flag" title="화면에서 발신 날짜를 못 읽어 수신 시각으로 배치">날짜?</span>')
        room_tag = "" if room else f'<span class="room">{_esc(rec.get("room"))}</span>'
        groups.setdefault(label, []).append(
            f'<li><div class="meta"><span class="time" title="서버 수신 {_esc(_received_kst(rec))}">'
            f"{_esc(_time_cell(rec, sent))}</span>"
            f'<span class="sender">{_esc(rec.get("sender") or "?")}</span>{room_tag}{"".join(flags)}</div>'
            f'<div class="text">{_esc(rec.get("text"))}</div></li>'
        )
    sections = [
        f'<section><h3>{_esc(label)} <span class="muted">{len(items)}건</span></h3><ul>{"".join(items)}</ul></section>'
        for label, items in sorted(groups.items(), reverse=True)
    ]

    generated = now.astimezone(KST).strftime("%Y-%m-%d %H:%M")
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="referrer" content="no-referrer">
<title>카카오 수집 조회</title>
<style>
:root {{ --bg:#fafaf9; --fg:#1c1917; --muted:#78716c; --line:#e7e5e4; --card:#fff; --accent:#b45309; --flag:#fef3c7; --flagfg:#92400e; }}
@media (prefers-color-scheme: dark) {{
  :root {{ --bg:#1c1917; --fg:#f5f5f4; --muted:#a8a29e; --line:#44403c; --card:#292524; --accent:#fbbf24; --flag:#451a03; --flagfg:#fcd34d; }}
}}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--fg); font:15px/1.5 -apple-system, "Apple SD Gothic Neo", "Noto Sans KR", sans-serif; }}
main {{ max-width:860px; margin:0 auto; padding:16px; }}
h1 {{ font-size:20px; margin:4px 0 12px; }}
h2 {{ font-size:16px; margin:24px 0 8px; }}
h3 {{ font-size:14px; margin:20px 0 6px; color:var(--accent); position:sticky; top:0; background:var(--bg); padding:6px 0; }}
form {{ display:flex; flex-wrap:wrap; gap:8px; align-items:center; }}
select, button {{ font:inherit; padding:6px 10px; border:1px solid var(--line); border-radius:6px; background:var(--card); color:var(--fg); }}
button {{ cursor:pointer; }}
.muted {{ color:var(--muted); font-weight:normal; }}
.table-wrap {{ overflow-x:auto; }}
table {{ border-collapse:collapse; width:100%; font-size:14px; }}
th, td {{ text-align:left; padding:6px 8px; border-bottom:1px solid var(--line); white-space:nowrap; }}
td.num, th.num {{ text-align:right; font-variant-numeric:tabular-nums; }}
ul {{ list-style:none; margin:0; padding:0; }}
li {{ background:var(--card); border:1px solid var(--line); border-radius:8px; padding:8px 10px; margin:6px 0; }}
.meta {{ display:flex; flex-wrap:wrap; gap:8px; align-items:baseline; font-size:13px; }}
.time {{ color:var(--muted); font-variant-numeric:tabular-nums; }}
.sender {{ font-weight:600; }}
.room {{ color:var(--muted); }}
.flag {{ background:var(--flag); color:var(--flagfg); border-radius:4px; padding:0 5px; font-size:12px; }}
.text {{ white-space:pre-wrap; overflow-wrap:anywhere; margin-top:2px; }}
</style>
</head>
<body>
<main>
<h1>카카오 수집 조회</h1>
<form method="get">
<input type="hidden" name="token" value="{_esc(token)}">
<select name="room">{"".join(room_options)}</select>
<select name="since">{"".join(since_options)}</select>
<button type="submit">조회</button>
<span class="muted">{len(messages)}건 · 기준 {generated} KST</span>
</form>
<h2>날짜별 건수</h2>
<div class="table-wrap"><table>
<thead><tr><th>발신일</th><th class="num">건수</th><th class="num">날짜 확인</th><th class="num">시각 확인</th><th>{"" if room else "방별"}</th></tr></thead>
<tbody>{"".join(stat_rows)}</tbody>
</table></div>
<p class="muted">서버(Modal Dict)는 7일간 쓰기가 없는 항목이 자동 만료되므로 오래된 날짜일수록 실제보다 적게 보일 수 있어요. '~' 시각과 '날짜?' 표시는 화면에서 못 읽어 서버 수신 시각으로 추정한 값입니다.</p>
<h2>메시지</h2>
{"".join(sections)}
</main>
</body>
</html>
"""
