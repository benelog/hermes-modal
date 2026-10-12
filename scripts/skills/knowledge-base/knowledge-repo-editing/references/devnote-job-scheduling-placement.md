# Devnote job scheduling link placement

Use this reference when adding Java/JVM background-job or scheduler libraries/tools to `devnote`.

## Placement pattern

- Prefer `content/job-scheduling.md` for general job scheduler/background processing libraries.
- If the item is specifically Quartz-related, `content/quartz.md` may be a secondary/related target, but general alternatives such as JobRunr fit better in `job-scheduling.md`.
- Existing `job-scheduling.md` is older and terse; a concise `## 오픈소스 라이브러리` section near the top is acceptable when adding modern OSS tools.

## Example: JobRunr

For `https://github.com/jobrunr/jobrunr`, ground the summary in GitHub API/README metadata:

- JVM library for fire-and-forget, delayed, scheduled, and recurring background jobs using Java 8 lambdas.
- Persists jobs in RDBMS options such as Postgres, MariaDB/MySQL, Oracle, SQL Server, DB2, SQLite, or MongoDB.
- Supports embedded use in existing apps, cluster-friendly scheduling via optimistic locking, and horizontal scaling with background job servers.

## Verification

After editing:

1. Run `git diff --check`.
2. Inspect `git diff -- content/job-scheduling.md`.
3. Commit/push by default unless the user asked otherwise.
4. Deployed page maps to `https://devnote.benelog.net/job-scheduling`; verify HTTP 200 if citing it back to the user.
