# Wiki YouTube link placement example

## Context

When the user asks to add a YouTube link to the wiki repo without naming a page, infer the likely topic page from the video's title and metadata, then add a compact grounded note.

## Example: `t-R6nDIY0Y0`

- URL: `https://www.youtube.com/watch?v=t-R6nDIY0Y0`
- oEmbed title: `1번 봐선 절대 모를 건축학개론의 숨은 설정과 디테일들`
- oEmbed author/channel: `박영하`
- Jina Reader exposed YouTube metadata including:
  - Posted: `Apr 11, 2023`
  - Description/chapter list
  - Chapters: `승민과 서연의 첫 키스`, `서연의 생일날 데이트`, `서연의 대조적인 배치`, `서연 그림의 복선 회수`, `전람회 CD의 행방`, `대칭적인 캐릭터`, `캐릭터의 연속성`, `건축학개론의 재수강`, `4가지 TMI`
- Best page: `wiki/content/건축.md`, because the title centers on the film 《건축학개론》 and an existing architecture page was present.
- Edit style used:
  - Add a specific `## 건축학개론` subsection below the existing `## 초고층 빌딩` section.
  - Add the raw YouTube link as a Markdown link.
  - Add 2 concise bullets grounded only in oEmbed/Jina metadata.

## Pattern

For similar wiki additions:

1. Fetch oEmbed for title/channel.
2. Fetch Jina Reader output and inspect the description/chapter region, not just the top of the page.
3. Search wiki content for an existing page matching the topic in the title.
4. If a broad page exists, add a topic subsection rather than creating a new page for a single video.
5. Summarize at metadata/chapter level when transcript access is blocked; do not invent transcript details.
6. Verify `git diff --check`, commit, rebase, push, then report the wiki deployed URL using the filename slug (percent-encoded if Korean).