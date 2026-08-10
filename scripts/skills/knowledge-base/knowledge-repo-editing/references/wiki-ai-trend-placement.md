# Wiki `ai-trend.md` placement notes

Use when adding AI industry/trend news links to `/root/workspace/benelog/wiki/content/ai-trend.md`.

Observed structure and placement conventions:

- Deployed pages:
  - `https://wiki.benelog.net/ai-trend` for `content/ai-trend.md`.
  - `https://wiki.benelog.net/ai-token-cost` for `content/ai-token-cost.md`.
- General model/company mood, product launches, frontier model news, AI-native product/team operating practices, and AX adoption case studies: place under `content/ai-trend.md` → `## 업계 분위기` unless a more specific section exists.
- For dated article additions under `## 업계 분위기`, insert at the date-sorted position (newest first) rather than appending; Korean newsletter dates can often be confirmed from the original HTML when Jina Reader omits them.
- AI company/business outlook and competitive positioning: place under `content/ai-trend.md` → `## 기업 전망`.
- Token usage, tokenmaxxing, AI inference/tooling spend, budget exhaustion, token-based pricing/monetization, input-vs-output token pricing, AI cost-management articles, and articles about employee AI-usage/token dashboards or pressure to maximize AI usage: place under `content/ai-token-cost.md` → `## Token 사용 경향`. `content/ai-trend.md` now only links to `[[ai-token-cost]]` for this topic.
- Keep each article as one top-level bullet with 3–5 concise nested Korean bullets; include source and date in parentheses when available.
- For multiple Korean news articles about similar token-cost trends, still add separate bullets when each has distinct evidence/angle, but keep summaries non-duplicative and emphasize each article's unique data point (e.g. productivity-vs-usage, subscription-to-usage billing, output-token premium, approval/allocation controls, AI FinOps/governance, employee-level dashboards, API budget limits).
- When adding several dated Token-section articles at once, insert each top-level bullet at its date-sorted position instead of appending as a batch; run `scripts/check-ai-trend-sort.py` afterward. For single `ai-token-cost.md` additions, insert by date descending manually and verify the nearby ordering.
- Prefer canonical resolved URLs over share URLs. For `share.google` links that resolve to Daum News (`v.daum.net/...`), cite the Daum URL unless the original publisher URL is easily available and clearly canonical.
- For Naver News articles, Jina Reader can return a large amount of navigation chrome before the article body. If direct HTML is accessible, extract `og:title`, `og:description`, `media_end_head_info_datestamp_time[data-date-time]`, and `#dic_area`/`article#dic_area` from the original page; this is often cleaner than relying only on Jina output.
- If the user asks to replace Token-section Korean news links with Naver sources, search Naver News by exact title and replace only confident matching `n.news.naver.com` results; keep already-Naver links unchanged and report non-matches.
- If the user asks for a messenger/plain-text roundup of Token-section articles, output only the requested scope (e.g. Korean press only) in repeated 3-line blocks: `기사제목 [언론사, YYYY-MM-DD]`, URL, blank line. Normalize all dates to ISO `YYYY-MM-DD`. Exclude non-Korean sources such as The Pragmatic Engineer when the user says 한국언론 기사만.
- If the user asks to sort linked articles by date, sort top-level bullets within each section by article/source date descending, keeping each bullet's nested summary attached. For items with no date in the note, visit the URL (Jina Reader first, then original page/search fallback) and add the confirmed source/date in parentheses when useful. If no reliable date is recoverable after visiting, leave the item at the bottom of its section and report that caveat.
- After sorting, verify with a small script that each section's known dates are descending; unresolved-date items should not break the check and should remain after dated items.
- Remote edits may rename headings or restructure this file. Pull/re-read the file before choosing the section and preserve upstream heading names during conflict resolution.

Session examples:

- MK article on `GPT-5.5-사이버`: added under `## 업계 분위기`.
- AI타임스 article on AI costs overtaking labor costs: added under `## Token 사용 경향`.
- DigitalToday/Jellyfish article on Claude Code users spending 10x tokens for only ~2x output: added under `## Token 사용 경향`.
- Seoul Economy/Daum article on output tokens costing 6x input tokens and Silicon Valley token monetization: added under `## Token 사용 경향`.
- eKorea article on AI efficiency and token-cost management controls: added under `## Token 사용 경향`.
- Traders Union Korean article on 24-hour AI agents and Korean company token-cost dashboards: Jina Reader worked despite direct site access returning 403; added under `## Token 사용 경향`.
- Inven/Krafton article on AI FinOps, deterministic-problem LLM overuse, and orchestration guardrails: added under `## Token 사용 경향`.
- CIO article on Claude API agent costs reaching $300/day and the need for API/team budget limits: added under `## Token 사용 경향`.
- DongA article on tokenmaxxing and token-cost dashboards: added under `## Token 사용 경향`.
- DigitalToday article on Claude Code cost estimate doubling from $6 to $13 per active user day: added under `## Token 사용 경향`.
- Amazon/MeshClaw article about employees inflating internal AI token usage due to perceived performance pressure: added under `content/ai-token-cost.md` → `## Token 사용 경향`.
- Korean articles about AI model routers, AI spend consoles, internal AI gateways, or companies building internal coding agents to reduce AI/tool-token spend belong in `content/ai-token-cost.md`, not `ai-trend.md`, because the durable angle is AI cost governance/orchestration rather than general product news. Place them under the month heading by source date descending; use one top-level bullet per article with 3–5 nested Korean bullets.
