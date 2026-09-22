"""
What a finished reply carries out of Chat history. The fields here are the ones that explain a
failure: the queries the turn ran, the filters it ran them under, and what it read.
"""

from __future__ import annotations

from typing import Any

from rag_benchmark.client import ActorClient

# The message that reproduced the undated-documents defect on staging: the turn inferred a September
# window over document dates the corpus does not have, read nothing, and said so.
MESSAGE: dict[str, Any] = {
    "id": "m1",
    "status": "COMPLETED",
    "content": "Tôi chưa tìm thấy tài liệu nào trong khoảng thời gian đó.",
    "sources": [],
    "activity": {
        "steps": [
            {
                "position": 0,
                "toolCallId": "c1",
                "toolName": "search_knowledge",
                "status": "COMPLETED",
                "durationMs": 4210,
                "queries": ["thông báo nhân sự tháng 9/2025", "quyết định bổ nhiệm 9/2025"],
                "filters": {
                    "sources": [],
                    "updated": {"from": "2025-09-01T00:00:00Z", "to": "2025-09-30T23:59:59Z"},
                },
                "documents": [],
                "citations": [],
            }
        ],
        "reasoning": [],
    },
}


def test_a_reply_keeps_the_queries_and_filters_of_every_step() -> None:
    reply = ActorClient._reply(MESSAGE, 4.5)

    assert reply.read_document_ids == []
    assert reply.steps == 1
    step = reply.timeline[0]
    assert step["tool"] == "search_knowledge"
    assert step["queries"] == [
        "thông báo nhân sự tháng 9/2025",
        "quyết định bổ nhiệm 9/2025",
    ]
    # Without this the run file cannot say why the answer was empty.
    assert step["filters"] == {
        "sources": [],
        "updated": {"from": "2025-09-01T00:00:00Z", "to": "2025-09-30T23:59:59Z"},
    }
    assert step["documents"] == []


def test_a_reply_keeps_what_each_step_read_even_when_it_is_not_cited() -> None:
    message = dict(MESSAGE)
    message["activity"] = {
        "steps": [
            dict(
                MESSAGE["activity"]["steps"][0],
                filters=None,
                documents=[
                    {
                        "documentId": "d1",
                        "generation": "g1",
                        "title": "Thông báo nhân sự",
                        "startOrdinal": 0,
                        "endOrdinal": 3,
                    }
                ],
            )
        ],
        "reasoning": [],
    }

    reply = ActorClient._reply(message, 1.0)

    assert reply.read_document_ids == ["d1"]
    assert reply.timeline[0]["filters"] is None
    assert reply.timeline[0]["documents"] == [
        {"documentId": "d1", "title": "Thông báo nhân sự", "from": 0, "to": 3}
    ]
