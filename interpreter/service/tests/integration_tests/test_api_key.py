"""MemoryOS API key on /v1 routes and expiry of uploaded files (see NOTICE.md)."""

from __future__ import annotations

import json
import time
from collections.abc import Iterator
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from memoryos_interpreter.auth import configured_api_key
from memoryos_interpreter.main import _cleanup_expired_files_once, create_app
from memoryos_interpreter.services.executor_base import HealthCheck
from memoryos_interpreter.services.executor_docker import DockerExecutor
from memoryos_interpreter.services.executor_factory import get_executor
from memoryos_interpreter.services.file_storage import FileStorageService


@pytest.fixture(autouse=True)
def _fresh_key(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    monkeypatch.delenv("API_KEY", raising=False)
    monkeypatch.delenv("API_KEY_FILE", raising=False)
    configured_api_key.cache_clear()
    get_executor.cache_clear()
    yield
    configured_api_key.cache_clear()
    get_executor.cache_clear()


def test_v1_routes_require_the_configured_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("API_KEY", "s3cret")
    client = TestClient(create_app())

    missing = client.get("/v1/files")
    wrong = client.get("/v1/files", headers={"X-Api-Key": "wrong"})
    right = client.get("/v1/files", headers={"X-Api-Key": "s3cret"})

    assert [missing.status_code, wrong.status_code, right.status_code] == [401, 401, 200]
    assert missing.headers["www-authenticate"] == "ApiKey"


def test_health_needs_no_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("API_KEY", "s3cret")

    with patch.object(DockerExecutor, "check_health", return_value=HealthCheck(status="ok")):
        response = TestClient(create_app()).get("/health")

    assert response.status_code == 200


def test_key_file_takes_precedence_over_the_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    key_file = tmp_path / "api-key.txt"
    key_file.write_text("from-file\n", encoding="utf-8")
    monkeypatch.setenv("API_KEY_FILE", str(key_file))
    monkeypatch.setenv("API_KEY", "from-env")
    client = TestClient(create_app())

    assert client.get("/v1/files", headers={"X-Api-Key": "from-file"}).status_code == 200
    assert client.get("/v1/files", headers={"X-Api-Key": "from-env"}).status_code == 401


def test_empty_key_file_is_rejected(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    key_file = tmp_path / "api-key.txt"
    key_file.write_text("\n", encoding="utf-8")
    monkeypatch.setenv("API_KEY_FILE", str(key_file))

    with pytest.raises(ValueError, match="API_KEY_FILE is empty"):
        configured_api_key()


def test_without_a_key_routes_stay_open() -> None:
    assert TestClient(create_app()).get("/v1/files").status_code == 200


def test_expired_uploaded_files_are_removed(tmp_path: Path) -> None:
    storage = FileStorageService(tmp_path)
    old_id = storage.save_file(b"old", "old.csv")
    new_id = storage.save_file(b"new", "new.csv")
    old_meta = tmp_path / f"{old_id}.meta.json"
    metadata = json.loads(old_meta.read_text(encoding="utf-8"))
    metadata["upload_time"] = time.time() - 3600
    old_meta.write_text(json.dumps(metadata), encoding="utf-8")

    with patch("memoryos_interpreter.main.get_file_storage", return_value=storage):
        _cleanup_expired_files_once(max_age_sec=900)

    assert [f.file_id for f in storage.list_files()] == [new_id]
