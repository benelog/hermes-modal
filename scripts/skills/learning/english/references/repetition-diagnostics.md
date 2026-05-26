# English cron repetition diagnostics

## Context

The daily English expression cron job uses `/root/.hermes/scripts/english_random_expressions.py` as a pre-run script. The job samples 5 expressions from the script's JSON output.

## Key discovery

`count_available` in the script output can be misleading for repetition analysis:

- `all_items` may contain many extracted markdown bullets, e.g. 210.
- The actual sampling pool is narrowed by:
  ```python
  pool = [x for x in all_items if x.get("notes")] or all_items
  chosen = random.SystemRandom().sample(pool, 5)
  ```
- Therefore the real random pool can be much smaller than `count_available` if only a subset has `notes`.
- `random.SystemRandom().sample(pool, 5)` prevents duplicates only within a single run; it does not remember previous cron outputs.

In the 2026-05-22..2026-05-26 Modal Hermes environment, the current values were:

- `count_available`: 210
- actual note-bearing pool size: 38
- recent cron output analyzed: 11 runs / 55 draws
- unique expressions drawn: 28
- repeated draws: 27
- expressions repeated at least once: 19
- probability of at least one overlap between two consecutive 5-samples from N=38: about 52.7%

## Useful diagnostic command

Run this to inspect recent cron outputs for job `157db4a4b6c3` and compare total vs actual pool size:

```bash
python - <<'PY'
from pathlib import Path
import re, json, collections, math, importlib.util

base = Path('/root/.hermes/cron/output/157db4a4b6c3')
runs = []
for f in sorted(base.glob('*.md')):
    txt = f.read_text(encoding='utf-8', errors='ignore')
    m = re.search(r'```\n(\{.*?\n\})\n```', txt, re.S)
    if not m:
        continue
    data = json.loads(m.group(1))
    runs.append((f.name, data.get('generated_at_kst'), [x.get('expression','') for x in data.get('items', [])], data.get('count_available')))

cnt = collections.Counter(e for _,_,exprs,_ in runs for e in exprs)
print('runs', len(runs))
print('total_draws', sum(len(exprs) for _,_,exprs,_ in runs))
print('unique_drawn', len(cnt))
print('repeat_draws', sum(c-1 for c in cnt.values() if c > 1))
print('expressions_repeated', sum(1 for c in cnt.values() if c > 1))
print('count_available values', sorted({c for *_, c in runs}))
for e, c in cnt.most_common():
    if c > 1:
        print(c, e[:140].replace('\n', ' '))

spec = importlib.util.spec_from_file_location('eng', '/root/.hermes/scripts/english_random_expressions.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
mod.sync_repo()
all_items = []
for path in sorted(mod.DOCS_DIR.rglob('*.md')):
    rel = path.relative_to(mod.DOCS_DIR).as_posix()
    if rel == 'index.md' or rel.startswith('lyrics/'):
        continue
    all_items.extend(mod.extract_items(path, path.read_text(encoding='utf-8', errors='ignore')))
pool = [x for x in all_items if x.get('notes')] or all_items
print('actual_pool_size', len(pool), 'all_items', len(all_items))
if len(pool) >= 5:
    k = 5
    N = len(pool)
    p_no = math.prod((N-k-i)/(N-i) for i in range(k))
    print('P consecutive overlap', 1 - p_no)
PY
```

## Likely fixes

- Persist expression history, e.g. `/root/.cache/hermes/english/history.json`, and exclude recently delivered expressions.
- Prefer a shuffled-cycle algorithm: shuffle the pool once, consume 5 at a time, reshuffle after the cycle is exhausted.
- Broaden the pool by improving parsing or allowing items without `notes` with generated examples.
- If changing cron behavior materially, remember the user's convention: mirror Hermes cron changes into `benelog/hermes-modal` with sanitized `cron_jobs/*.json` and scripts under `scripts/cron/`, then commit/push by default unless told otherwise.
