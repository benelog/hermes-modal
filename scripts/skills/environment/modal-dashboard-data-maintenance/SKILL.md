---
name: modal-dashboard-data-maintenance
description: Correct persistent records behind Modal-hosted dashboards, preserving unrelated data and verifying the rendered public page after a narrowly scoped Volume update.
---

# Modal dashboard data maintenance

Use when a user supplies a corrected value for an existing Modal-hosted dashboard, ranking table, or similar data-backed page. This is a data maintenance workflow, not a reason to redeploy application code or change schedules.

## Workflow

1. Fetch the public page and identify the existing record, column, labels, and source links. Treat user-supplied values as manual corrections; do not describe them as independently scraped facts.
2. Resolve date scope using the live clock in the dashboard's timezone. A UTC date can differ from a Korean dashboard's “today.” State the effective date in the completion message.
3. Discover the storage and schema before writing. Use prior session search if useful, local source inspection, and `modal app list` / `modal volume list`. Inspect collection, history persistence, manual-entry parsing, and rendering functions. Do not assume a locally available deployment file matches the deployed application.
4. Download the relevant Volume file to a unique local backup with `modal volume get <volume> <remote-path> <backup-path>`. Read it and confirm the exact date/store/entity exists.
5. Create a separate edited copy. Change only the requested field and any provenance fields justified by the schema. Preserve other dates, stores, notes, and collection timestamps. Keep manual-input markers when appropriate; do not imply a fresh automatic collection.
6. Validate JSON and the diff. For larger datasets, assert that unrelated records remain equal. Recheck remote data before overwriting if there may have been a concurrent collector run; merge the correction into the latest version rather than uploading a stale snapshot.
7. Upload the corrected file with `modal volume put --force <volume> <edited-path> <remote-path>`. Overwrite only the known data object in the user's requested scope, not an entire directory.
8. Fetch the actual public URL again and verify the requested value appears in the intended row with unrelated columns intact. A successful upload alone is not end-to-end verification. If the page remains stale, investigate the application's Volume reload/cache behavior before changing code or restarting services.
9. Reply concisely with the effective date, corrected value, verification result, and clickable dashboard URL. Distinguish data correction from changes to collection behavior.

## Pitfalls

- Never replace a whole history with a newly constructed single-record example.
- Do not use the conversation start date as “today.”
- A correction to today's value is not necessarily a day-over-day movement; do not invent a delta from the replaced value.
- Do not change cron jobs, redeploy, or commit runtime records to a source repository unless that is actually part of the task.
- Keep a backup for recovery, but avoid rollback that would erase a newer legitimate collection.

## References

- [Book ranking dashboard](references/book-ranking-dashboard.md): observed application layout, Volume schema, and verified maintenance commands. Rediscover current paths and resource names before reuse.
