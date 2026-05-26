# English cron repetition diagnostics

## Context

The daily English expression cron job uses `/root/.hermes/scripts/english_random_expressions.py` as a pre-run script. The job samples 5 expressions from the script's JSON output and formats them into a Telegram-friendly Korean briefing.

## Current behavior after 2026-05-26 fix

The script was changed to reduce repeats by broadening the real sampling pool:

```python
if rel == "index.md" or rel == "recommendation.md" or rel.startswith("lyrics/"):
    continue
# ... collect all_items ...
pool = all_items
chosen = random.SystemRandom().sample(pool, 5)
```

It also emits diagnostic fields:

- `count_available`: number of eligible extracted items
- `count_with_notes`: how many of those have nested notes/examples
- `sample_pool_size`: actual random sampling pool size
- `sample_pool_strategy`: currently `all_extracted_items`

In the 2026-05-26 Modal Hermes environment, the fix changed the pool from a notes-only 38 items to 152 eligible items after excluding `index.md`, `recommendation.md`, and `lyrics/`. Consecutive-run overlap probability for 5-item samples fell from about 52.7% at N=38 to about 15.6% at N=152.

## Historical bug / symptom

Older versions looked like this:

```python
pool = [x for x in all_items if x.get("notes")] or all_items
chosen = random.SystemRandom().sample(pool, 5)
```

This made `count_available` misleading: `all_items` could be much larger than the real sampling pool, and `random.SystemRandom().sample(pool, 5)` prevents duplicates only within one briefing, not across cron runs.

Observed before the fix over 2026-05-22..2026-05-26:

- `count_available`: 210
- actual note-bearing pool size: 38
- recent cron output analyzed: 11 runs / 55 draws
- unique expressions drawn: 28
- repeated draws: 27
- expressions repeated at least once: 19

## Useful diagnostic command

Run this to inspect recent cron outputs for job `157db4a4b6c3` and compare output diagnostics with actual repeats:

```bash
python - <<'PY'
from pathlib import Path
import re, json, collections, math

base = Path('/root/.hermes/cron/output/157db4a4b6c3')
runs = []
for f in sorted(base.glob('*.md')):
    txt = f.read_text(encoding='utf-8', errors='ignore')
    m = re.search(r'```\n(\{.*?\n\})\n```', txt, re.S)
    if not m:
        continue
    data = json.loads(m.group(1))
    runs.append((
        f.name,
        data.get('generated_at_kst'),
        [x.get('expression','') for x in data.get('items', [])],
        data.get('count_available'),
        data.get('count_with_notes'),
        data.get('sample_pool_size'),
        data.get('sample_pool_strategy'),
    ))

cnt = collections.Counter(e for _,_,exprs,*_ in runs for e in exprs)
print('runs', len(runs))
print('total_draws', sum(len(exprs) for _,_,exprs,*_ in runs))
print('unique_drawn', len(cnt))
print('repeat_draws', sum(c-1 for c in cnt.values() if c > 1))
print('expressions_repeated', sum(1 for c in cnt.values() if c > 1))
print('count_available values', sorted({c for *_, c, _, _, _ in runs if c is not None}))
print('count_with_notes values', sorted({c for *_, c, _, _ in runs if c is not None}))
print('sample_pool_size values', sorted({c for *_, c, _ in runs if c is not None}))
print('sample_pool_strategy values', sorted({c for *_, c in runs if c}))
for e, c in cnt.most_common():
    if c > 1:
        print(c, e[:140].replace('\n', ' '))

N = next((pool for *_, pool, strategy in reversed(runs) if pool), None)
if N and N >= 5:
    k = 5
    p_no = math.prod((N-k-i)/(N-i) for i in range(k))
    print('P consecutive overlap for latest pool', 1 - p_no)
PY
```

## Further fixes if repeats are still too frequent

- Persist expression history, e.g. `/root/.cache/hermes/english/history.json`, and exclude recently delivered expressions.
- Prefer a shuffled-cycle algorithm: shuffle the pool once, consume 5 at a time, reshuffle after the cycle is exhausted.
- Improve markdown parsing to extract structured notes/examples from more pages without including non-expression recommendation lists.
- If changing cron behavior materially, mirror Hermes cron changes into `benelog/hermes-modal` with sanitized `cron_jobs/*.json` and scripts under `scripts/cron/`, then commit/push by default unless told otherwise.
