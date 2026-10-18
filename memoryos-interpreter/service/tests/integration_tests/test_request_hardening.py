"""MemoryOS fixes to upstream request handling, wrapping and Docker calls (see NOTICE.md)."""

from __future__ import annotations

import io
import subprocess
from unittest.mock import patch
from urllib.parse import quote

import pytest
from fastapi.testclient import TestClient

from memoryos_interpreter.main import create_app
from memoryos_interpreter.services.executor_docker import (
    DOCKER_COMMAND_TIMEOUT_SEC,
    DockerExecutor,
    container_sleep_seconds,
)


def _client() -> TestClient:
    return TestClient(create_app())


@pytest.mark.parametrize(
    "file_id",
    [
        "/etc/passwd",
        "../../etc/passwd",
        "nonexistent-id",
        "{12345678-1234-5678-1234-567812345678}",
    ],
)
def test_file_ids_that_are_not_issued_uuids_are_not_found(file_id: str) -> None:
    client = _client()

    response = client.post(
        "/v1/execute",
        json={
            "code": "print(open('staged').read())",
            "timeout_ms": 5000,
            "files": [{"path": "staged", "file_id": file_id}],
        },
    )

    assert response.status_code == 404
    assert client.delete(f"/v1/files/{quote(file_id, safe='')}").status_code == 404


def test_download_keeps_non_latin1_filename() -> None:
    client = _client()
    name = "báo cáo tháng 9 — Hà Nội.pdf"
    upload = client.post(
        "/v1/files", files={"file": (name, io.BytesIO(b"%PDF-1.7"), "application/pdf")}
    )
    assert upload.status_code == 201

    download = client.get(f"/v1/files/{upload.json()['file_id']}")

    assert download.status_code == 200
    disposition = download.headers["content-disposition"]
    assert disposition.isascii()
    assert f"filename*=UTF-8''{quote(name, safe='')}" in disposition


def test_user_code_cannot_shadow_wrapper_names() -> None:
    response = _client().post(
        "/v1/execute", json={"code": "ast = None\ntree = []\nnamespace = 0\n42", "timeout_ms": 5000}
    )

    assert response.status_code == 200
    assert response.json()["stdout"] == "42\n"


def test_workspace_snapshot_keeps_leading_dots() -> None:
    code = "open('.env', 'w').write('A=1')\nopen('..cache', 'w').write('x')\nNone"

    response = _client().post("/v1/execute", json={"code": code, "timeout_ms": 5000})

    assert response.status_code == 200
    assert {".env", "..cache"} <= {entry["path"] for entry in response.json()["files"]}


@pytest.mark.parametrize(("timeout_ms", "seconds"), [(1, 11), (1000, 11), (1001, 12), (60_000, 70)])
def test_container_sleep_is_timeout_in_seconds_plus_margin(timeout_ms: int, seconds: int) -> None:
    assert container_sleep_seconds(timeout_ms) == seconds


def test_timed_out_container_start_kills_the_container() -> None:
    executor = DockerExecutor()
    responses = [subprocess.TimeoutExpired("docker run", DOCKER_COMMAND_TIMEOUT_SEC), None]

    with (
        patch(
            "memoryos_interpreter.services.executor_docker.subprocess.run", side_effect=responses
        ) as run,
        pytest.raises(RuntimeError, match="Timed out starting container"),
    ):
        executor.execute_python(code="1", stdin=None, timeout_ms=1000, max_output_bytes=1000)

    assert run.call_args_list[0].kwargs["timeout"] == DOCKER_COMMAND_TIMEOUT_SEC
    assert run.call_args_list[1].args[0][1] == "kill"
