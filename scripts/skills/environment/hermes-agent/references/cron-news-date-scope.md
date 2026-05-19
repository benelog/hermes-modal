# Cron news date-scope correction

## Context

The user first asked for news searches to use only dates up to yesterday. That was saved as a broad preference and applied to the AI-cost morning briefing cronjob. The user then corrected the desired scope:

- Only the current 7 AM KST news briefing cronjob should have a date window.
- That cronjob should search/filter **yesterday and today** news.
- Other news-search commands should have no default date restriction unless explicitly requested.

## Operational rule

When modifying Hermes cronjobs for news briefings:

1. Inspect the live jobs first (`cronjob list`; if needed read `~/.hermes/cron/jobs.json`).
2. Identify the matching cronjob by name/schedule/prompt. In this session it was `AI 비용 관련 뉴스 아침 브리핑`, schedule `0 22 * * *` UTC (= 7 AM KST).
3. Update only that cronjob prompt. Avoid global memory/preferences that alter all future news searches.
4. Verify with `cronjob list` after update.

## Good prompt wording for the 7 AM briefing

```text
중요한 날짜 기준:
- 이 7시 뉴스 브리핑 cronjob에 한해서, 뉴스 검색/선별 대상은 반드시 실행일 기준 “어제와 오늘” 발행된 기사로 제한한다.
- 실행일 기준 어제 또는 오늘 날짜가 확인되는 기사만 포함한다.
- 기사 날짜가 확인되지 않는 경우에는 어제/오늘 기사임을 확인할 수 있을 때만 포함한다.
- 이 날짜 기준은 이 cronjob 전용 조건이다.
```

## Pitfall

Do not generalize cronjob-specific search filters into ad hoc web/news search behavior. If a broad memory already exists, replace it with a scoped statement rather than leaving contradictory preferences.
