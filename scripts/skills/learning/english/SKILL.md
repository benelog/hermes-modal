---
name: english
description: Run the user's "오늘의 영어 표현 5개" briefing from benelog/english materials.
---

# English Expression Briefing

## Trigger

Use this skill when the user types `/english` or asks for "오늘의 영어 표현 5개".

## Procedure

1. Run the existing Hermes cron pre-run script to collect five random expressions:
   ```bash
   python /root/.hermes/scripts/english_random_expressions.py
   ```
2. Parse the JSON output. It contains `items`, each with:
   - `expression`
   - `notes`
   - `page_title`
   - `section`
   - `source_file`
   - `url`
3. Reply in Korean, concise and Telegram-friendly, with the title:
   `## 오늘의 영어 표현 5개`
4. For each expression, include:
   - the English expression
   - a short Korean meaning/explanation inferred from `notes` when present, or from the expression/page context when `notes` is empty
   - 1 useful example sentence when possible; use a provided note as the example when it is already an example
   - source link from `url`
5. Mention the source site/repo briefly at the end.

## Constraints

- Do not ask for clarification for a normal `/english` invocation.
- Use the script output rather than inventing expressions.
- Keep the response compact enough for a daily learning message.

## Troubleshooting repetition in the cron job

If the user reports that daily English expressions repeat too often, inspect the pre-run script and cron outputs rather than assuming the `count_available` value is the real sampling pool. In the current implementation, the script may extract many markdown bullets but sample only from items with `notes`:

```python
pool = [x for x in all_items if x.get("notes")] or all_items
chosen = random.SystemRandom().sample(pool, 5)
```

This prevents duplicates only within one briefing and keeps no cross-run history. Use `references/repetition-diagnostics.md` for the analysis command, probability check, and likely fixes such as persisted history or shuffled-cycle selection.
