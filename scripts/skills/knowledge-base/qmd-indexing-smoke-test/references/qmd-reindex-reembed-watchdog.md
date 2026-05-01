# QMD reindex/reembed watchdog notes

Session pattern worth reusing:

- Initial status after model switch:
  - `Total: 995`
  - `VectorIndex: yes`
  - `Missing: 0`
  - `Mismatch: 995`
  - effective model: `hf:Qwen/Qwen3-Embedding-0.6B-GGUF/Qwen3-Embedding-0.6B-Q8_0.gguf`
  - stored model: `hf:ggml-org/embeddinggemma-300M-GGUF/embeddinggemma-300M-Q8_0.gguf`
  - search policy: `kiwi-cong-shadow-v1`, `untracked index`, `Indexed: 0/995`
- `qmd update` fixed Korean lexical search:
  - `Search Policy Health: clean`
  - `Indexed: 995/995`
- `qmd embed -f --max-docs-per-batch ...` failed because this CLI version only accepted `qmd embed [-f|--force]` despite broader help text elsewhere.
- `qmd embed --force` downloaded the Qwen model and began rebuilding. Interruption after 20 docs changed status to:
  - `Missing: 975`
  - `Mismatch: 0`
  - stored Qwen embeddings: 20 docs
- This is not regression: mismatched old-model embeddings were invalidated/replaced, so progress should be counted by current-model stored docs increasing and Missing decreasing.
- Resume with plain `qmd embed` after mismatch is 0; it fills missing current-model embeddings.
- CPU-only embedding can be slow and background processes may exit before completion. Use repeated `qmd status` and restart `qmd embed` when Missing > 0 and no embed process is running.
- In the Modal Hermes environment, `ps`, `free`, and `uptime` may be absent. To detect a running embed process, scan `/proc/*/cmdline`:

```python
import os
found=[]
for pid in filter(str.isdigit, os.listdir('/proc')):
    try:
        cmd=open(f'/proc/{pid}/cmdline','rb').read().replace(b'\0',b' ').decode(errors='replace').strip()
        if cmd and 'qmd embed' in cmd and 'python3 - <<' not in cmd:
            found.append((pid, cmd))
    except Exception:
        pass
print(found)
```

Hermes cron observations from this session:

- A cron job created with `schedule: every 4m` initially worked but later next-run times drifted or jumped unexpectedly.
- Recreating the job and using `*/4 * * * *` did not fully prevent drift; later it skipped from `08:36` to `09:36`.
- Updating back to `every 4 minutes` recalculated the immediate next run correctly.
- Treat Hermes cron status as advisory for watchdogs; verify `last_run_at`, `next_run_at`, and the underlying process/QMD status. If the user relies on close-interval checks, manually verify once after the next scheduled time or use a single long-running shell loop instead of Hermes cron when continuous enforcement matters.

Final successful completion in this session:

- `qmd embed` processed remaining `242 documents` / `439 chunks`, `Errors: 0`.
- Final `qmd status`:
  - `Missing: 0`
  - `Mismatch: 0`
  - `Embedding Model Health: clean`
  - stored Qwen embeddings: `995 docs`
  - search policy: `clean`, `Indexed: 995/995`
