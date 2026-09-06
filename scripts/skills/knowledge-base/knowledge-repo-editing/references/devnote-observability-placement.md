# Devnote observability link placement

Use this reference when adding observability, telemetry, logging/metrics/tracing, OpenTelemetry, or monitoring platform/tool links to `devnote/content/observability.md`.

## Current page shape

- Intro/top-level survey links can stay at the top.
- `## Concept` is for conceptual explanations such as MELT (metrics, events, logs, traces).
- `## Trace` is for tracing-specific articles and local tracing/OpenTelemetry developer tools.
  - Example placement: `otel-desktop-viewer` fits here because it receives OpenTelemetry traces, metrics, and logs locally and exposes a localhost UI.
- `## Platform` is appropriate for full observability platforms that cover multiple signals.
  - Example placement: `OpenObserve` fits here because it is an observability platform for logs, metrics, traces, RUM/session replay, SLO, and LLM observability.
- Keep `## Related` at the bottom.

## Summary style

For GitHub observability tools, use one concise nested Korean bullet grounded in GitHub API/README metadata. Prefer concrete capabilities and ports when the README states them, but avoid volatile metrics like current star counts in the note body.
