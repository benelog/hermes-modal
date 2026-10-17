# Periodic milestone rollover and commit activity issues

## Discover before writing

1. Resolve the tracking repository from conversation and authenticated GitHub repository metadata. Do not create a new repository because the user's spelling differs from an already established repository.
2. Read all milestones, including closed ones, with pagination. Determine the latest scheduled milestone by due date, not creation number alone. Read its predecessor and existing issues.
3. For a requested two-week rollover, compute the new due date as the latest due date plus 14 days. New period starts the following day. Infer the preceding period from its predecessor's actual end date; do not force historical irregular periods into fourteen days. Use Asia/Seoul day boundaries converted to UTC for API queries.
4. Collect the current timestamp and cap an ongoing period at that observation time.
5. List accessible user-owned repositories with pagination; use pushed_at only to shortlist candidates. Retrieve actual commits for each period, paginating fully. Avoid an author filter for owned repositories when agent/bot-assisted work should count.
6. Supplement with GitHub commit search for external contributions, e.g. `author:<user> -user:<user> committer-date:<range>`. Filter returned commit timestamps against exact KST boundaries. Clearly report that default-branch history does not include unmerged branch work; search results may have index limitations.
7. Inspect existing milestone issues to avoid duplicate activity summaries. Ordinary task issues are not automatically duplicates of a repository activity summary.

## Draft and validate

- Stage drafts in a local JSON artifact before remote writes: period boundaries, repo, title, body, commit count, and evidence URLs.
- One issue per repository per period. Titles should include the period and repository name; include owner for external repositories.
- Bodies contain 3–7 `- ` bullet lines in Korean. Summarize concrete changes by theme and attach actual commit URLs. For a one-commit repository, distinguish the change, its scope/context, and evidence rather than inventing additional work to fill lines.
- Check that each cited commit is among retrieved evidence. Record search coverage and limitations separately.
- Apply the user's explicit exclusions to the final draft set before writing. An exclusion for one request is not a permanent account-wide rule.

## Safe remote application

1. Authenticate using available configured credentials without printing secrets. Prefer standard configured authentication. If an authorized existing repository remote contains credentials, they can be read internally and used for GitHub API requests; never log the raw remote URL or token.
2. Look for an existing target milestone by both intended title and date before creating it. Read back its due date after creation: compare the calendar day, not merely a successful HTTP response. If timestamp normalization shifts the calendar day, use an explicit midday UTC timestamp for the intended date and read back again.
3. Snapshot the prior milestone's unfinished task issue numbers before creating activity issues. Move only that snapshot, preserving open state. Do not accidentally carry over a newly created activity summary left open by an interrupted run.
4. Create each activity issue in its corresponding milestone, then immediately PATCH it to `state=closed`, `state_reason=completed`.
5. Persist a checkpoint immediately after every successful create, move, or close. Store returned IDs and URLs. Preserve the original moved-issue snapshot across retries.
6. On interruption or ambiguous failure, inspect remote state and any surviving execution before retrying. A failed parent call does not prove no writes happened. Reconcile checkpoints and exact titles first; do not rerun a non-idempotent create loop blindly.
7. A duplicate-title validation error may mean the target milestone already exists with a different normalized date. Read it, correct only the intended field, and continue using its ID rather than creating another.

## Verification

- GET each returned issue ID to confirm body format, expected milestone, closed/completed state, and exclusions.
- Confirm every carried-forward task remains open in the new milestone.
- If a list response temporarily omits a just-written issue, retrieve the known ID directly and retry bounded read-only verification instead of recreating it.
- Confirm the new milestone's actual due date. Do not close the old milestone unless requested.
- Report linked milestone periods, counts of migrated tasks and created/closed activity issues, excluded repositories, observation cutoff, and coverage limitations. Do not list dozens of issues unless useful or requested.
