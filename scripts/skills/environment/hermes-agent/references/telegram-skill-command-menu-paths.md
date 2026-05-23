# Telegram skill command menu path mismatch in Modal

## Symptom

A user-installed skill such as `english` exists under `~/.hermes/skills`, `get_skill_commands()` returns `/english`, and `resolve_skill_command_key('english')` resolves correctly, but Telegram's `/` command picker does not show `english` (or any skill-based commands).

This is different from a missing skill: direct dispatch may still work when the user types `/english`, while menu registration omits it.

## Reproduction / diagnostic commands

```bash
python - <<'PY'
from agent.skill_commands import get_skill_commands, resolve_skill_command_key
from hermes_cli.commands import telegram_menu_commands, _collect_gateway_skill_entries, _sanitize_telegram_name, telegram_bot_commands
from tools.skills_tool import SKILLS_DIR
from pathlib import Path

cmds = get_skill_commands()
print('/english in commands:', '/english' in cmds)
print('resolve english:', resolve_skill_command_key('english'))
menu, hidden = telegram_menu_commands(100)
print('english in telegram menu:', [m for m in menu if m[0] == 'english'])
print('menu len/hidden:', len(menu), hidden)
print('SKILLS_DIR raw:', SKILLS_DIR)
print('SKILLS_DIR resolved:', SKILLS_DIR.resolve())
for k, info in sorted(cmds.items()):
    if k == '/english' or 'english' in k:
        sp = info.get('skill_md_path', '')
        print(k, sp, 'resolved:', Path(sp).resolve())
        print('raw startswith resolved skills dir:', sp.startswith(str(SKILLS_DIR.resolve())))
        print('resolved startswith resolved skills dir:', str(Path(sp).resolve()).startswith(str(SKILLS_DIR.resolve())))
PY
```

## Root cause observed in Modal

In Modal, `~/.hermes/skills` may resolve to a volume path like:

```text
/__modal/volumes/<volume-id>/skills
```

But `agent.skill_commands.get_skill_commands()` can return raw skill paths like:

```text
/root/.hermes/skills/learning/english/SKILL.md
```

`hermes_cli.commands._collect_gateway_skill_entries()` compared the raw `skill_md_path` string to `str(SKILLS_DIR.resolve())`:

```python
_skills_dir = str(SKILLS_DIR.resolve())
skill_path = info.get("skill_md_path", "")
if not skill_path.startswith(_skills_dir):
    continue
```

The raw path does not start with the resolved volume path, so every skill is filtered out of the Telegram menu. Core commands still appear, making this look like only `/english` failed.

## Fix pattern

Normalize both paths before comparing:

```python
from pathlib import Path

_skills_dir = str(SKILLS_DIR.resolve())
skill_path = info.get("skill_md_path", "")
try:
    resolved_skill_path = str(Path(skill_path).resolve())
except Exception:
    resolved_skill_path = skill_path

if not resolved_skill_path.startswith(_skills_dir):
    continue
```

Apply the same idea to hub-dir filtering if needed.

## Verification

After patching/restarting the gateway if necessary:

```bash
python - <<'PY'
from hermes_cli.commands import telegram_menu_commands
menu, hidden = telegram_menu_commands(100)
print([m for m in menu if m[0] == 'english'])
print('hidden:', hidden, 'count:', len(menu))
PY
```

Expected: `('english', ...)` appears in the Telegram menu list.

Also check direct dispatch separately:

```bash
python - <<'PY'
from agent.skill_commands import resolve_skill_command_key
print(resolve_skill_command_key('english'))
PY
```

Expected: `/english`.
