"""One benchmark question asked through MemoryOS Chat, as a person in a role would ask it."""

from __future__ import annotations

import re
import time
import uuid

from memoryos_bench.api import Api

# A turn that reads, rewrites and selects can take minutes on a scanned corpus.
REPLY_TIMEOUT_SECONDS = 600
# Enough of each cited span for a faithfulness judge; a span longer than this is cut.
PASSAGE_CHARS = 6000
TITLE_ID = re.compile(r"\.(pdf|docx?|xlsx?)$", re.IGNORECASE)


def manifest_id(title: str) -> str:
    """An upload's title is its manifest file name: the id is the title less its extension."""
    return TITLE_ID.sub("", title or "")


def ask(api: Api, question: str, persona_id: str | None) -> dict:
    """Asks in a fresh session, waits for the finished reply and deletes the session."""
    session = api.post(
        "/api/chat/sessions", {"title": "memoryos-benchmark", "personaId": persona_id}
    )
    try:
        started = time.monotonic()
        accepted = api.post(
            f"/api/chat/sessions/{session['id']}/messages",
            {
                "parentMessageId": session["rootMessageId"],
                "clientRequestId": str(uuid.uuid4()),
                "text": question,
            },
        )
        message = _finished(
            api, session["id"], accepted["userMessageId"], accepted["assistantMessageId"]
        )
        seconds = time.monotonic() - started
        return _reply(api, message, seconds, accepted)
    finally:
        api.delete(f"/api/chat/sessions/{session['id']}")


def _finished(api: Api, session_id: str, user_id: str, assistant_id: str) -> dict:
    deadline = time.monotonic() + REPLY_TIMEOUT_SECONDS
    while True:
        rows = api.get(f"/api/chat/sessions/{session_id}/messages", after=user_id, limit=1)
        message = next((row for row in rows if row["id"] == assistant_id), None)
        if message and message["status"] != "RUNNING":
            return message
        if time.monotonic() > deadline:
            raise TimeoutError(f"reply {assistant_id} did not finish in {REPLY_TIMEOUT_SECONDS}s")
        time.sleep(2)


def _passage(api: Api, source: dict) -> str:
    """The text of a cited span, read through the same reader a person opens a citation with."""
    start, end = source.get("startOrdinal", 0), source.get("endOrdinal", 0)
    texts, cursor = [], start
    while cursor <= end and sum(map(len, texts)) < PASSAGE_CHARS:
        page = api.get(
            f"/api/chat/documents/{source['documentId']}",
            generation=source["generation"],
            **{"from": cursor},
        )
        passages = [p for p in page["passages"] if start <= p["ordinal"] <= end]
        texts += [p["content"] for p in passages]
        if not page.get("hasMore") or not page["passages"]:
            break
        cursor = page["passages"][-1]["ordinal"] + 1
    return "\n".join(texts)[:PASSAGE_CHARS]


def _reply(api: Api, message: dict, seconds: float, accepted: dict) -> dict:
    sources = message.get("sources") or []
    steps = (message.get("activity") or {}).get("steps") or []
    cited = [
        {
            "citationId": s.get("citationId"),
            "document": manifest_id(s.get("title", "")),
            "title": s.get("title"),
            "text": _passage(api, s) if s.get("documentId") and s.get("generation") else "",
        }
        for s in sources
    ]
    read = sorted(
        {manifest_id(d.get("title", "")) for step in steps for d in step.get("documents") or []}
        - {""}
    )
    return {
        "answer": message.get("content") or "",
        "status": message["status"],
        "failureCode": message.get("failureCode"),
        "refusalReason": message.get("refusalReason"),
        "sources": cited,
        "readDocuments": read,
        "citedDocuments": sorted({c["document"] for c in cited} - {""}),
        "context": "\n\n".join(f"[{c['citationId']}] {c['title']}\n{c['text']}" for c in cited),
        "steps": [
            {
                "tool": s.get("toolName"),
                "status": s.get("status"),
                "durationMs": s.get("durationMs"),
                "queries": s.get("queries") or [],
                "filters": s.get("filters"),
            }
            for s in steps
        ],
        "seconds": round(seconds, 1),
        "modelConfigurationId": accepted.get("modelConfigurationId"),
        "fallbackReason": accepted.get("fallbackReason"),
    }
