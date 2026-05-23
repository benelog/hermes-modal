# Telegram skill command menu path filtering in Modal

Session-derived debugging path for cases where a skill command such as `/english` exists but does not appear in the Telegram `/` command menu.

## Symptom

- `get_skill_commands()` includes the skill command, e.g. `/english`.
- `resolve_skill_command_key('english')` returns `/english`, so direct dispatch can work.
- `telegram_menu_commands(max_commands=100)` returns only core commands and omits all skill commands.
- Telegram `setMyCommands` therefore does not show the skill in the client autocomplete/menu.

## Root cause observed in Modal

In `hermes_cli/commands.py::_collect_gateway_skill_entries`, the menu builder filters skills by comparing `skill_md_path` against `str(SKILLS_DIR.resolve())`:

```python
from tools.skills_tool import SKILLS_DIR
_skills_dir = str(SKILLS_DIR.resolve())
skill_path = info.get("skill_md_path", "")
if not skill_path.startswith(_skills_dir):
    continue
```

In Modal, these can refer to the same mounted directory but have different textual forms:

```text
SKILLS_DIR.resolve() -> /__modal/volumes/<volume-id>/skills
skill_md_path        -> /root/.hermes/skills/learning/english/SKILL.md
Path(skill_md_path).resolve() -> /__modal/volumes/<volume-id>/skills/learning/english/SKILL.md
```

The raw string prefix check fails, causing every skill command to be excluded from the Telegram menu.

## Diagnostic commands

```bash
python - <<'PY'
from pathlib import Path
from agent.skill_commands import get_skill_commands, resolve_skill_command_key
from hermes_cli.commands import telegram_menu_commands
from tools.skills_tool import SKILLS_DIR

print('SKILLS_DIR raw:', SKILLS_DIR)
print('SKILLS_DIR resolved:', SKILLS_DIR.resolve())
for key, info in sorted(get_skill_commands().items()):
    sp = info.get('skill_md_path', '')
    print(key, 'raw=', sp)
    print('  raw startswith resolved dir:', sp.startswith(str(SKILLS_DIR.resolve())))
    print('  resolved startswith resolved dir:', str(Path(sp).resolve()).startswith(str(SKILLS_DIR.resolve())))

print('resolve english:', resolve_skill_command_key('english'))
menu, hidden = telegram_menu_commands(100)
print('menu length:', len(menu), 'hidden:', hidden)
print('english in menu:', [item for item in menu if item[0] == 'english'])
PY
```

## Fix direction

Normalize both sides before filtering, for example:

```python
from pathlib import Path

_skills_dir = SKILLS_DIR.resolve()
_hub_dir = (SKILLS_DIR / '.hub').resolve()
...
skill_path = info.get('skill_md_path', '')
try:
    resolved_skill_path = Path(skill_path).resolve()
except Exception:
    continue
if not str(resolved_skill_path).startswith(str(_skills_dir)):
    continue
if str(resolved_skill_path).startswith(str(_hub_dir)):
    continue
```

Prefer `Path.is_relative_to()` when available and appropriate:

```python
if not resolved_skill_path.is_relative_to(_skills_dir):
    continue
```

## Pitfalls

- `/reload-skills` or recreating the skill does not fix this class of failure; the skill registry can already contain the command while the Telegram menu builder filters it out.
- Distinguish menu registration from command dispatch. A command can be runnable by typing it manually even when absent from Telegram autocomplete.
- In Telegram, command names use underscores instead of hyphens; use `resolve_skill_command_key('name_with_underscore')` when checking dispatch for skills whose file names contain hyphens.
