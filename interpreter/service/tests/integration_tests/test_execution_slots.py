"""MemoryOS concurrent-execution limit (MAX_CONCURRENT_EXECUTIONS, see NOTICE.md)."""

from __future__ import annotations

import threading
from collections.abc import Generator, Iterator
from unittest.mock import patch

import httpx
import pytest
from fastapi.testclient import TestClient

from memoryos_interpreter.api import routes
from memoryos_interpreter.api.routes import ExecutionSlots
from memoryos_interpreter.main import create_app
from memoryos_interpreter.services.executor_base import (
    ExecutionResult,
    StreamChunk,
    StreamEvent,
    StreamResult,
)

REQUEST = {"code": "print(1)", "timeout_ms": 5000}


@pytest.fixture(autouse=True)
def _one_slot() -> Iterator[None]:
    with patch.object(routes, "_execution_slots", ExecutionSlots(1)):
        yield


def _post_in_thread(path: str, results: list[httpx.Response]) -> threading.Thread:
    thread = threading.Thread(
        target=lambda: results.append(TestClient(create_app()).post(path, json=REQUEST))
    )
    thread.start()
    return thread


def test_execute_beyond_the_limit_gets_429_until_a_slot_frees() -> None:
    started, finish = threading.Event(), threading.Event()

    def blocking_execute(**_: object) -> ExecutionResult:
        started.set()
        finish.wait(10)
        return ExecutionResult("1\n", "", 0, False, 1, ())

    with patch.object(routes, "execute_python", side_effect=blocking_execute):
        results: list[httpx.Response] = []
        thread = _post_in_thread("/v1/execute", results)
        assert started.wait(10)

        rejected = TestClient(create_app()).post("/v1/execute", json=REQUEST)
        finish.set()
        thread.join(10)
        after = TestClient(create_app()).post("/v1/execute", json=REQUEST)

    assert rejected.status_code == 429
    assert rejected.headers["retry-after"] == "1"
    assert [r.status_code for r in results] == [200]
    assert after.status_code == 200


def test_failed_execution_releases_its_slot() -> None:
    with patch.object(routes, "execute_python", side_effect=ValueError("bad file path")):
        first = TestClient(create_app()).post("/v1/execute", json=REQUEST)
        second = TestClient(create_app()).post("/v1/execute", json=REQUEST)

    assert [first.status_code, second.status_code] == [422, 422]


def test_stream_holds_its_slot_until_the_stream_ends() -> None:
    started, finish = threading.Event(), threading.Event()

    def blocking_stream(**_: object) -> Generator[StreamEvent, None, None]:
        yield StreamChunk(stream="stdout", data="1\n")
        started.set()
        finish.wait(10)
        yield StreamResult(exit_code=0, timed_out=False, duration_ms=1, files=())

    with patch.object(routes, "execute_python_streaming", side_effect=blocking_stream):
        results: list[httpx.Response] = []
        thread = _post_in_thread("/v1/execute/stream", results)
        assert started.wait(10)

        rejected = TestClient(create_app()).post("/v1/execute/stream", json=REQUEST)
        finish.set()
        thread.join(10)
        after = TestClient(create_app()).post("/v1/execute/stream", json=REQUEST)

    assert rejected.status_code == 429
    assert [r.status_code for r in results] == [200]
    assert "event: result" in results[0].text
    assert after.status_code == 200


def test_zero_disables_the_limit() -> None:
    slots = ExecutionSlots(0)

    acquired = [slots.try_acquire() for _ in range(10)]

    assert all(slot is not None for slot in acquired)
