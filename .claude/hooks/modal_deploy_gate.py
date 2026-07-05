#!/usr/bin/env python3
"""PreToolUse(Bash): force a confirmation prompt on `modal deploy`.

The Modal app is the production Telegram/Kakao bot; deploys must never slip
through on auto-approved Bash permissions.
"""
import json
import sys

data = json.load(sys.stdin)
cmd = (data.get("tool_input") or {}).get("command", "")
if "modal deploy" in cmd:
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "ask",
            "permissionDecisionReason": (
                "modal deploy touches the PRODUCTION Telegram/Kakao bot. "
                "Confirm with the user before deploying."
            ),
        }
    }))
