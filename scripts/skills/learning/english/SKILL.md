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
   - a short Korean meaning/explanation inferred from the notes
   - 1 useful example sentence when possible
   - source link from `url`
5. Mention the source site/repo briefly at the end.

## Constraints

- Do not ask for clarification for a normal `/english` invocation.
- Use the script output rather than inventing expressions.
- Keep the response compact enough for a daily learning message.
