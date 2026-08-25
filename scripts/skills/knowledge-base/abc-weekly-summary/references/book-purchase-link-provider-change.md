# ABC weekly summary book purchase-link provider changes

Use when changing the default bookstore used for `구매 링크:` in the ABC weekly summary.

## Durable lesson

Changing the bookstore in `abc-weekly-summary` is not just a SKILL.md text edit. The scheduled job also uses the pre-run enrichment script `abc_book_enrichment.py`, and its output can continue to surface old provider candidates if the script is not updated and re-verified.

## Required update checklist

1. Patch `abc-weekly-summary` SKILL.md:
   - Suggested output example under `## 공유·추천된 책`.
   - Additional output rule that says which bookstore to search when the purchase link was not shared.
2. Patch the enrichment helper used by the cron job:
   - Script path: `/root/.hermes/scripts/abc_book_enrichment.py`.
   - Rename provider-specific functions/JSON fields to the new provider where appropriate.
   - Update search query constraints, e.g. for Kyobo: `site:product.kyobobook.co.kr/detail`.
   - Normalize provider URLs to the preferred stable detail URL format.
3. Verify script syntax and stale-provider strings:
   ```bash
   python3 -m py_compile /root/.hermes/scripts/abc_book_enrichment.py
   python3 - <<'PY'
   from pathlib import Path
   text = Path('/root/.hermes/scripts/abc_book_enrichment.py').read_text()
   for needle in ['yes24_purchase', 'yes24_purchase_candidates', 'm.yes24.com', 'site:yes24.com/goods/detail']:
       print(needle, needle in text)
   for needle in ['kyobo_purchase_search', 'kyobo_purchase_candidates', 'site:product.kyobobook.co.kr/detail']:
       print(needle, needle in text)
   PY
   ```
4. Re-load the skill with `skill_view(name='abc-weekly-summary')` after patching. Do not trust a previous in-memory copy.
5. For a manual re-run, call `cronjob(action='run', job_id=...)`, then run `hermes cron tick`, then verify:
   - `cronjob(action='list')` shows `last_status: ok` and a new `last_run_at`.
   - The newest file under `/root/.hermes/cron/output/<job_id>/` contains the new provider wording and not the old provider example.

## Current expected provider

As of this note, the default purchase-link provider is Kyobo Book Centre:

- Preferred URL shape: `https://product.kyobobook.co.kr/detail/...`
- Search constraint: `site:product.kyobobook.co.kr/detail`
- Output candidate key: `kyobo_purchase_candidates`
