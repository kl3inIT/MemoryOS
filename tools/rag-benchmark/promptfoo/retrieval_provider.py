"""promptfoo provider for the retrieval stage: one search as `exec`, no Chat, no query rewrite.

Returns the ranked documents to depth 50 with their section texts, and the full indexed text of the
question's gold documents, so the assertions can score ranking and extraction from the same reply. Which
search runs (hybrid, or keyword or vector alone on the probe build) is the API's, not the provider's.
"""

from __future__ import annotations

import json
import os
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from memoryos_bench.api import Api  # noqa: E402
from memoryos_bench.auth import RoleSession, TokenStore  # noqa: E402
from memoryos_bench.chat import manifest_id  # noqa: E402

ORIGIN = os.environ.get("MEMORYOS_ORIGIN", "https://memoryos.72-62-193-33.nip.io")
HOME = Path(os.environ.get("MEMORYOS_BENCH_HOME", Path.home() / ".memoryos-bench"))
ROLE = "exec"
DEPTH = 50
PAGE_SIZE = 20
MANIFEST = Path(__file__).resolve().parent.parent / "corpus" / "manifest.json"

_lock = threading.Lock()
_session: RoleSession | None = None
_texts: dict[str, str] = {}


def _api() -> Api:
    global _session
    with _lock:
        if _session is None:
            _session = RoleSession(TokenStore(HOME / "tokens.json"), ROLE)
        return Api(ORIGIN, _session)


def ranked(api: Api, query: str) -> list[dict]:
    """The readable documents in search order, to DEPTH, each with its section texts."""
    results: list[dict] = []
    page = 0
    while len(results) < DEPTH:
        body = api.post("/api/search", {"query": query, "page": page, "pageSize": PAGE_SIZE})
        results += [
            {
                "document": manifest_id(r["title"]),
                "sections": [s["content"] for s in r["sections"]],
            }
            for r in body["results"]
        ]
        if not body.get("hasMore"):
            break
        page += 1
    return results[:DEPTH]


def indexed_text(api: Api, document: str) -> str:
    """Every passage of a manifest document as the index holds it, found by its file name."""
    with _lock:
        if document in _texts:
            return _texts[document]
    names = {d["id"]: d["fileName"] for d in json.loads(MANIFEST.read_text("utf-8"))["documents"]}
    body = api.post("/api/search", {"query": names[document], "page": 0, "pageSize": 10})
    hit = next((r for r in body["results"] if manifest_id(r["title"]) == document), None)
    if hit is None:
        raise LookupError(f"{document} is not searchable as {ROLE}")
    passages, cursor = [], 0
    while True:
        page = api.get(
            f"/api/chat/documents/{hit['documentId']}",
            generation=hit["generation"],
            **{"from": cursor},
        )
        passages += [p["content"] for p in page["passages"]]
        if not page.get("hasMore") or not page["passages"]:
            break
        cursor = page["passages"][-1]["ordinal"] + 1
    text = "\n".join(passages)
    with _lock:
        _texts[document] = text
    return text


def call_api(prompt: str, options: dict, context: dict) -> dict:
    variables = context.get("vars", {})
    try:
        api = _api()
        gold = json.loads(variables["gold"])
        texts, missing = {}, []
        for document in gold:
            # A gold document the name search cannot find is not in the index: its quotes count as lost,
            # and the ranking of the row is still scored.
            try:
                texts[document] = indexed_text(api, document)
            except LookupError:
                missing.append(document)
        return {
            "output": {
                "ranked": ranked(api, variables["question"]),
                "goldText": texts,
                "missingGold": missing,
            }
        }
    except Exception as error:
        return {"error": f"{type(error).__name__}: {error}"[:500]}
