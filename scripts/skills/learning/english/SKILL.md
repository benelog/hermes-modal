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
6. Generate exactly ONE combined audio using `text_to_speech` after drafting the text. Read each of the five English expressions followed by its matching English example, in the same order as the message. Use brief English numbering and sentence breaks; omit Korean explanations, source URLs, Markdown, and source annotations. Replace placeholders such as [Name] with a natural example name in the spoken version. Keep the script below 4,000 characters.
7. Include the tool's real `MEDIA:/absolute/path` in the final answer, on its own line, so Telegram delivers a playable audio/voice attachment alongside the text. Never invent a media path or claim generation succeeded without a successful tool result. If TTS fails, deliver the text anyway and briefly disclose that audio generation failed. Do not enable global `/voice tts`; this audio applies only to the English briefing.

## Scheduled execution

When the cron pre-run context already contains the expression JSON, use that JSON instead of running the selection script again. The scheduled job must enable the `tts` toolset. Cron delivery extracts `MEDIA:` tags from the final response and forwards the audio attachment.

## Verification and pitfalls

- Test the configured TTS provider with real synthesis before claiming audio works. Edge TTS may return HTTP 403 from hosted environments; updating the package alone may not fix it. Report the failure and obtain the user's choice of an alternative provider rather than silently changing global voice configuration.
- Cron must include `tts` in `enabled_toolsets`, and the final response must retain the returned `MEDIA:` line. Prompt changes alone do not prove synthesis or delivery works.
- Mirror scheduled prompt changes in `benelog/hermes-modal/cron_jobs/english-random-expressions.json`; preserve the existing schedule and origin, exclude runtime/private fields, and commit/push.

## Constraints

- Do not ask for clarification for a normal `/english` invocation.
- Use the script output rather than inventing expressions.
- Keep the response compact enough for a daily learning message.
