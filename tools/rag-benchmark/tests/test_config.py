"""Configuration errors the CLI must report instead of raising."""

from __future__ import annotations

import pytest

from rag_benchmark.config import Config, ConfigError


def test_a_non_numeric_setting_is_a_configuration_error(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MEMORYOS_BASE_URL", "https://memoryos.test")
    monkeypatch.setenv("MEMORYOS_ISSUER", "https://auth.test/realms/memoryos")
    monkeypatch.setenv("MEMORYOS_BENCHMARK_ACTORS", "exec")
    monkeypatch.setenv("MEMORYOS_BENCHMARK_TOKEN_EXEC", "token")
    monkeypatch.setenv("MEMORYOS_JUDGE_TRIALS", "three")

    with pytest.raises(ConfigError, match="MEMORYOS_JUDGE_TRIALS"):
        Config.from_environment()

    monkeypatch.setenv("MEMORYOS_JUDGE_TRIALS", "3")
    assert Config.from_environment().judge_trials == 3


def test_a_run_can_name_the_model_that_answers(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MEMORYOS_BASE_URL", "https://memoryos.test")
    monkeypatch.setenv("MEMORYOS_ISSUER", "https://auth.test/realms/memoryos")
    monkeypatch.setenv("MEMORYOS_BENCHMARK_ACTORS", "exec")
    monkeypatch.setenv("MEMORYOS_BENCHMARK_TOKEN_EXEC", "token")

    assert Config.from_environment().model_configuration_id is None

    monkeypatch.setenv("MEMORYOS_BENCHMARK_MODEL", "033a5abc-c1c1-4765-9b36-541c20c0ae39")

    assert (
        Config.from_environment().model_configuration_id == "033a5abc-c1c1-4765-9b36-541c20c0ae39"
    )
