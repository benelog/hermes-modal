# Platform research findings

Durable facts about the Modal/Hermes runtime. KakaoTalk collection facts moved to
[benelog/kakao-to-google-sheet](https://github.com/benelog/kakao-to-google-sheet) `docs/research.md`.

## Modal platform

- `cron_tick` (07:00/19:00 KST) wakes a container to run `hermes cron tick`, keeping the gateway scale-to-zero; the hermes-home volume is committed at end of each tick, so mid-tick file writes persist (usable as watermarks).
- `hermes -z PROMPT` one-shot prints only the final LLM text to stdout — a Modal endpoint can reuse the resident Hermes model as a subprocess summarizer, no extra API key.
