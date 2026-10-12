"""promptfoo provider that returns a stored reply instead of asking MemoryOS: re-grades a past run with
changed checks, without new turns. `$MEMORYOS_REPLAY` names a JSON file of {"<id>|<role>": output}."""

from __future__ import annotations

import json
import os
from pathlib import Path

_replies: dict | None = None


def call_api(prompt: str, options: dict, context: dict) -> dict:
    global _replies
    if _replies is None:
        _replies = json.loads(Path(os.environ["MEMORYOS_REPLAY"]).read_text(encoding="utf-8"))
    variables = context.get("vars", {})
    key = f"{variables['id']}|{variables['role']}"
    if key not in _replies:
        return {"error": f"no stored reply for {key}"}
    return {"output": _replies[key]}
