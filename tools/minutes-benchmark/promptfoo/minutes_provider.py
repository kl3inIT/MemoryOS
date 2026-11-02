"""promptfoo provider: has MemoryOS write a benchmark meeting's minutes again and returns them.

Runs inside the promptfoo container (standard library only) as the `minutes` role of the benchmark token store
(`$MEMORYOS_BENCH_HOME/tokens.json`). The test's `copy` var names the copied meeting; the provider asks for its
minutes to be rewritten, waits until they are written or failed, and returns them with each item's line in the
transcript, so the assertions compare them with the answer key.
"""

from __future__ import annotations

import fcntl
import json
import os
import sys
import threading
import time
from pathlib import Path

# The RAG benchmark's login and API client, from the repository or from where the server keeps them.
TOOLS = Path(os.environ.get("MEMORYOS_BENCH_TOOLS", Path(__file__).resolve().parents[2] / "rag-benchmark"))
sys.path.insert(0, str(TOOLS))

from memoryos_bench.api import Api, ApiError  # noqa: E402
from memoryos_bench.auth import RoleSession, TokenStore  # noqa: E402

ORIGIN = os.environ.get("MEMORYOS_ORIGIN", "https://memoryos.72-62-193-33.nip.io")
HOME = Path(os.environ.get("MEMORYOS_BENCH_HOME", Path.home() / ".memoryos-bench"))
ROLE = "minutes"
# The worker writes long meetings in a few minutes; a model that is busy or locked by 9Router takes longer.
TIMEOUT_SECONDS = 900
POLL_SECONDS = 5
WRITING = {"PENDING", "RUNNING"}

_lock = threading.Lock()
_session: RoleSession | None = None


def _api() -> Api:
    global _session
    with _lock:
        if _session is None:
            _session = RoleSession(TokenStore(HOME / "tokens.json"), ROLE)
        return Api(ORIGIN, _session)


def minutes(detail: dict) -> dict:
    """The written minutes as the assertions read them: items with their line, owner and quote."""
    lines = {utterance["id"]: number for number, utterance in enumerate(detail.get("utterances") or [], 1)}

    def item(entry: dict, action: bool) -> dict:
        shaped = {
            "text": entry.get("text") or "",
            "line": lines.get(entry.get("sourceUtteranceId")),
            "quote": entry.get("quote"),
        }
        if action:
            voice = entry.get("ownerSpeaker")
            shaped["owner"] = (
                {"track": voice["track"], "label": voice["label"]} if voice
                else {"name": entry["owner"]} if entry.get("owner") else None
            )
            shaped["due"] = entry.get("due")
        return shaped

    written = detail.get("minutes") or {}
    return {
        "status": written.get("status"),
        "failure": written.get("failure"),
        "decisions": [item(entry, False) for entry in written.get("decisions") or []],
        "actions": [item(entry, True) for entry in written.get("actions") or []],
        "speakers": [
            {"track": s["track"], "label": s["label"], "name": s.get("name")} for s in detail.get("speakers") or []
        ],
        "lines": [
            {"n": number, "speaker": f"{u['track']}/{u.get('speaker') or ''}", "text": u["text"]}
            for number, u in enumerate(detail.get("utterances") or [], 1)
        ],
    }


def call_api(prompt: str, options: dict, context: dict) -> dict:
    copy = context.get("vars", {}).get("copy")
    if not copy:
        return {"error": "the test names no copied meeting"}
    # Repeats of one meeting may run at once; each must wait for the other's minutes, not read them.
    with open(Path(os.environ.get("TMPDIR", "/tmp")) / f"minutes-{copy}.lock", "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            return _write(copy)
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def _write(copy: str) -> dict:
    api = _api()
    path = f"/api/meetings/{copy}"
    try:
        api.post(f"{path}/minutes?discardEdits=true")
    except ApiError as error:
        return {"error": str(error)}
    deadline = time.monotonic() + TIMEOUT_SECONDS
    started = time.monotonic()
    while True:
        time.sleep(POLL_SECONDS)
        try:
            detail = api.get(path)
        except ApiError as error:
            return {"error": str(error)}
        status = (detail.get("minutes") or {}).get("status")
        if status not in WRITING:
            break
        if time.monotonic() > deadline:
            return {"error": f"minutes still {status} after {TIMEOUT_SECONDS} s"}
    output = minutes(detail)
    output["seconds"] = round(time.monotonic() - started)
    if output["status"] != "READY":
        return {"error": f"minutes {output['status']}: {output['failure']}", "output": output}
    return {"output": output}


if __name__ == "__main__":  # one meeting by hand: python minutes_provider.py <copy id>
    print(json.dumps(call_api("", {}, {"vars": {"copy": sys.argv[1]}}), ensure_ascii=False, indent=2))
