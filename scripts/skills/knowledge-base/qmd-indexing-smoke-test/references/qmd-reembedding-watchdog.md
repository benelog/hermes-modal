# QMD re-embedding watchdog notes

Session-derived operational details for QMD maintenance in Modal/Hermes.

## Observed status transition

Initial unhealthy state:

- Total documents: 995
- Vector index: yes
- Missing/needs embedding: 0
- Mismatch: 995
- Effective model: `hf:Qwen/Qwen3-Embedding-0.6B-GGUF/Qwen3-Embedding-0.6B-Q8_0.gguf`
- Stored model: `hf:ggml-org/embeddinggemma-300M-GGUF/embeddinggemma-300M-Q8_0.gguf`
- Search policy: `kiwi-cong-shadow-v1`, untracked index, indexed `0/995`

After `qmd update`:

- Search policy became clean, indexed `995/995`
- Embedding mismatch remained until rebuilding embeddings

After interrupted `qmd embed --force`:

- Stored current-model embeddings: 20 docs
- Missing: 975
- Mismatch: 0

This looked like regression only because old wrong-model embeddings were replaced by current-model progress accounting.

## Commands that worked

```bash
qmd status
qmd update
qmd embed --force   # only when stored embeddings are for the wrong model
qmd embed           # resume missing embeddings after interruption
```

`qmd embed -f --max-docs-per-batch 128 --max-batch-mb 64` failed in this environment with:

```text
Usage: qmd embed [-f|--force]
```

Despite help text mentioning batch options, this build accepted only `-f|--force` for embed.

## Watchdog process check without ps

Some environments lack `ps`. Use `/proc` scanning:

```bash
python3 - <<'PY'
import os
found=[]
for pid in filter(str.isdigit, os.listdir('/proc')):
    try:
        cmd=open(f'/proc/{pid}/cmdline','rb').read().replace(b'\0',b' ').decode(errors='replace').strip()
        if cmd and 'qmd embed' in cmd and 'python3 - <<' not in cmd:
            found.append((pid, cmd))
    except Exception:
        pass
print('QMD_EMBED_RUNNING=' + ('yes' if found else 'no'))
for pid,cmd in found:
    print(f'QMD_EMBED_PID={pid} CMD={cmd}')
PY
```

If status shows `Missing > 0` or `Mismatch > 0` and no process is running, restart:

```bash
nohup qmd embed >> /tmp/qmd-embed-watchdog.log 2>&1 & echo $!
```

## Cron reporting lesson

A cron job repeatedly updated between `every 2m`, `every 4m`, and `*/4 * * * *` reported `last_status: ok` but skipped expected runs. In this case, remove and recreate the job instead of continuing to update it. Prefer a fresh explicit schedule like:

```cron
*/4 * * * *
```

Include in each report:

- UTC check time
- total docs
- current-model embedded docs if visible from `Stored: ... (N docs)`
- missing embeddings
- mismatch count
- search policy health/indexed count
- whether `qmd embed` was running or restarted with PID
