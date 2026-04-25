---
name: modal-soul-md-personality-check
description: Check and apply Modal Hermes personality rules from SOUL.md before falling back to QMD or other knowledge sources when the user references Modal, SOUL.md, personality, identity, or a special caller phrase/name.
---

# Modal SOUL.md Personality Check

## When to use

Use this skill when the user mentions any of:

- `SOUL.md`
- Modal / modal deployment / "modal에 배포"
- Hermes personality or identity rules
- a special name/caller phrase such as `장창모`
- "think again", "check before QMD", or similar corrections suggesting a runtime/personality file should have been consulted

## Procedure

1. **Check for SOUL.md first, before QMD.**
   - Search likely runtime/script locations for `SOUL.md` or `*SOUL*`.
   - Known locations observed in Modal Hermes:
     - `/opt/hermes-modal/scripts/SOUL.md`
     - `/__modal/volumes/vo-rr8imRljnj1ELO7N6OJ899/SOUL.md`

2. **Read the file contents exactly.**
   - Use `read_file` rather than guessing from memory.
   - Preserve exact trigger text and exact required response prefix.

3. **Apply the rule immediately if it is currently applicable.**
   - In the observed `SOUL.md`, the rule was:
     - When `장창모` addresses the assistant, begin exactly with:
       `창모정신을 살려서 최선을 다해 대답하겠습니다!`
     - Then continue with the normal helpful answer.

4. **Only then consult QMD or session history if needed.**
   - QMD may contain incidental references to the same name, but runtime/personality rules take precedence for identity/response-style questions.
   - If QMD is still needed, use targeted lexical searches first for exact names/phrases, and reduce reranking/candidate limits if timeouts occur.

5. **Save stable findings appropriately.**
   - If a stable environment/personality rule is discovered, save a compact declarative memory.
   - Do not save temporary task progress.

## Pitfalls

- Do not assume a name is only a person from QMD diary/wiki entries; it may be a runtime personality trigger.
- Do not stop after QMD if the user explicitly hints that a Modal/SOUL.md deployment detail is missing.
- Preserve exact wording. For personality trigger prefixes, small wording changes can violate the rule.

## Verification

Before finalizing:

- Confirm `SOUL.md` was actually found and read, or state that it was not found after searching likely locations.
- Confirm whether the discovered rule changes the current response format.
- If the current user is the trigger/caller, ensure the final answer begins with the exact required prefix.
