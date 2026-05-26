---
name: people-context-questioning
description: Formulate grounded questions for a specific person from benelog/QMD/wiki records and public activity/news, prioritizing person-specific context over generic topic matches.
---

# People Context Questioning

## When to use

Use this skill when the user asks you to ask, draft, or send a question to a specific person based on wiki/QMD/benelog records, news, activity logs, or search results.

Examples:

- “wiki 기록을 바탕으로 장창모에게 궁금한 점을 물어봐줘”
- “OO님 관련 기록 보고 질문 하나 만들어줘”
- “이 사람 뉴스 기반으로 심화 질문 보내줘”
- “레전드 언론보도 보고 각자에게 질문해줘”

## Core principle

For a named person, **start from that person's own activity/news/career records**, not from generic topical pages that merely contain plausible themes.

If the user names “장창모”, a generic page like `창의성.md` is weaker than a people/news page such as `레전드 언론보도.md` containing 장창모’s MD, 파즐, 엠디라이브, 리징, or 상권 개발 records.

## Procedure

1. **Identify the target person and intended delivery**
   - Extract the person's name and whether the user wants a draft or wants you to send it.
   - If sending, list/check messaging targets before sending unless the target is already obvious from the active chat context.

2. **Search person-specific records first**
   - Search QMD/benelog for exact name, aliases, company names, and likely group pages.
   - Search titles and filenames as well as content. People-specific records may be on group pages like `레전드 언론보도.md`, not files named after the person.
   - If QMD get fails because the indexed path differs from the real filename, use `mcp_qmd_status` to find the repo root, then `search_files`/`read_file` on the local repo.

3. **Prefer specific personal evidence over generic themes**
   - Rank sources roughly:
     1. The person's own news/interview/activity entries.
     2. Company/project pages tied to the person.
     3. Group pages that include the person.
     4. Generic topic pages only if no person-specific evidence exists.
   - Do not overfit to a generic wiki topic just because it sounds interesting.

4. **Deepen with web search when names/companies are ambiguous**
   - Search the web for exact Korean company/person strings, quoted variants, and alternate spellings.
   - Disambiguate similarly named companies before tying them to the person.
   - State uncertainty when a company name appears unrelated or cannot be connected.

5. **Draft the question**
   - Mention the concrete evidence briefly: roles, dates, projects, company names, article titles, or career transitions.
   - Ask a synthesis question that only this person could answer, e.g. how their market-reading method changed across roles, what earlier experience still transfers, or what surprised them in the latest project.
   - Keep it respectful and concise enough for Telegram.

6. **Cite deployed URLs when reporting back to the user**
   - When citing QMD/benelog source files, follow `qmd-result-citation`: convert local paths to deployed URLs.
   - For wiki flat content, `content/<name>.md` maps to `https://wiki.benelog.net/<name>.html`.

7. **If you sent an unsuitable question**
   - Acknowledge the mismatch directly.
   - Re-search person-specific records.
   - Send a corrected follow-up question rather than merely explaining what should have been sent.

## Useful reference

- See `references/changmo-mdlive-mdworks-disambiguation.md` for a concrete example: 장창모 questions should use `레전드 언론보도.md` and 엠디라이브 records; “엠디웍스” search results were unrelated or ambiguous.

## Pitfalls

- Do not ask a broad philosophical question when the user's named target has concrete news/activity records available.
- Do not assume a company with a similar name is connected to the person; verify representative name, article text, or company record.
- Do not cite raw local paths in user-facing messages; use deployed URLs for benelog/QMD files.
- Avoid sending multiple speculative questions. Prefer one strong, grounded, person-specific question.
