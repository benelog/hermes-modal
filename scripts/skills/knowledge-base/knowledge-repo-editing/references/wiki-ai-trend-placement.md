# Wiki `ai-trend.md` placement notes

Use when adding AI industry/trend news links to `/root/workspace/benelog/wiki/content/ai-trend.md`.

Observed structure and placement conventions:

- Deployed page: `https://wiki.benelog.net/ai-trend`.
- General model/company mood, product launches, and frontier model news: place under `## 업계 분위기` unless a more specific section exists.
- AI company/business outlook and competitive positioning: place under `## 기업 전망`.
- Token usage, tokenmaxxing, AI inference/tooling spend, budget exhaustion, token-based pricing/monetization, input-vs-output token pricing, and AI cost-management articles: place under `## Token 사용 경향`.
- Keep each article as one top-level bullet with 3–5 concise nested Korean bullets; include source and date in parentheses when available.
- For multiple Korean news articles about similar token-cost trends, still add separate bullets when each has distinct evidence/angle, but keep summaries non-duplicative and emphasize each article's unique data point (e.g. productivity-vs-usage, subscription-to-usage billing, output-token premium, approval/allocation controls).
- Prefer canonical resolved URLs over share URLs. For `share.google` links that resolve to Daum News (`v.daum.net/...`), cite the Daum URL unless the original publisher URL is easily available and clearly canonical.
- If the user asks to replace Token-section Korean news links with Naver sources, search Naver News by exact title and replace only confident matching `n.news.naver.com` results; keep already-Naver links unchanged and report non-matches.
- If the user asks for a messenger/plain-text roundup of Token-section articles, output only the requested scope (e.g. Korean press only) in repeated 3-line blocks: `기사제목 [언론사, YYYY-MM-DD]`, URL, blank line. Normalize all dates to ISO `YYYY-MM-DD`. Exclude non-Korean sources such as The Pragmatic Engineer when the user says 한국언론 기사만.
- Remote edits may rename headings or restructure this file. Pull/re-read the file before choosing the section and preserve upstream heading names during conflict resolution.

Session examples:

- MK article on `GPT-5.5-사이버`: added under `## 업계 분위기`.
- AI타임스 article on AI costs overtaking labor costs: added under `## Token 사용 경향`.
- DigitalToday/Jellyfish article on Claude Code users spending 10x tokens for only ~2x output: added under `## Token 사용 경향`.
- Seoul Economy/Daum article on output tokens costing 6x input tokens and Silicon Valley token monetization: added under `## Token 사용 경향`.
- eKorea article on AI efficiency and token-cost management controls: added under `## Token 사용 경향`.
