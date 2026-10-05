"""promptfoo provider: asks MemoryOS Chat as the test's role and returns the reply with what it read.

Runs inside the promptfoo container (standard library only). The test's vars name the `role` and the
`mode` (`standard` asks the built-in assistant, `grounded` the `Benchmark - grounded` agent). Offline
tokens come from `$MEMORYOS_BENCH_HOME/tokens.json`.
"""

from __future__ import annotations

import os
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from memoryos_bench.api import Api  # noqa: E402
from memoryos_bench.auth import RoleSession, TokenStore  # noqa: E402
from memoryos_bench.chat import ask  # noqa: E402

ORIGIN = os.environ.get("MEMORYOS_ORIGIN", "https://memoryos.72-62-193-33.nip.io")
HOME = Path(os.environ.get("MEMORYOS_BENCH_HOME", Path.home() / ".memoryos-bench"))
GROUNDED_AGENT = "Benchmark - grounded"

_lock = threading.Lock()
_sessions: dict[str, RoleSession] = {}
_agents: dict[str, str] = {}


def _api(role: str) -> Api:
    with _lock:
        if role not in _sessions:
            _sessions[role] = RoleSession(TokenStore(HOME / "tokens.json"), role)
        return Api(ORIGIN, _sessions[role])


def _grounded_agent(api: Api, role: str) -> str:
    """The agent as this role sees it; a role it is not shared with has no grounded agent."""
    with _lock:
        if role in _agents:
            return _agents[role]
    found = next(
        (
            p["id"]
            for p in api.get("/api/chat/personas", view="ALL", offset=0, limit=100)
            if p["name"] == GROUNDED_AGENT
        ),
        "",
    )
    with _lock:
        _agents[role] = found
    return found


def call_api(prompt: str, options: dict, context: dict) -> dict:
    variables = context.get("vars", {})
    role, mode = variables["role"], variables.get("mode", "standard")
    try:
        api = _api(role)
        persona = None
        if mode == "grounded":
            persona = _grounded_agent(api, role)
            if not persona:
                raise RuntimeError(f"{role} cannot see the {GROUNDED_AGENT} agent; run provision")
        reply = ask(api, variables["question"], persona)
        if reply["status"] != "COMPLETED":
            # A failed turn is a system error, not a refusal; grading it would score it as declining.
            return {
                "error": f"turn {reply['status']}: {reply.get('failureCode')}",
                "metadata": reply,
            }
        reply["askedThrough"] = "grounded agent" if persona else "built-in assistant"
        return {"output": reply}
    except Exception as error:  # noqa: BLE001 - promptfoo records the error on the row
        return {"error": f"{type(error).__name__}: {error}"[:500]}
