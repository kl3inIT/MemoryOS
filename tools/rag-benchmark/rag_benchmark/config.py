"""
Run configuration. Every credential comes from the environment; none is written to a file in the
repository.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


class ConfigError(RuntimeError):
    """A required setting is missing; the message names the variable to set."""


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ConfigError(f"Set {name} (see .env.example)")
    return value


def _suffix(label: str) -> str:
    return label.upper().replace("-", "_")


@dataclass(frozen=True)
class Actor:
    """
    One identity the benchmark asks as; questions refer to it by label.

    The realm enables neither direct access grants nor implicit flows, so a password is never a
    credential here: an actor authenticates through its own service-account client (client
    credentials), whose subject is bound to the Actor, or through a bearer token for one run.
    """

    label: str
    client_id: str | None = None
    client_secret: str | None = None
    token: str | None = None

    @staticmethod
    def from_environment(label: str) -> Actor:
        suffix = _suffix(label)
        token = os.environ.get(f"MEMORYOS_BENCHMARK_TOKEN_{suffix}", "").strip()
        client_id = os.environ.get(f"MEMORYOS_BENCHMARK_CLIENT_{suffix}", "").strip()
        client_secret = os.environ.get(f"MEMORYOS_BENCHMARK_SECRET_{suffix}", "").strip()
        if not token and not (client_id and client_secret):
            raise ConfigError(
                f"Set MEMORYOS_BENCHMARK_TOKEN_{suffix}, or "
                f"MEMORYOS_BENCHMARK_CLIENT_{suffix} and MEMORYOS_BENCHMARK_SECRET_{suffix}"
            )
        return Actor(
            label=label,
            client_id=client_id or None,
            client_secret=client_secret or None,
            token=token or None,
        )


@dataclass(frozen=True)
class Config:
    base_url: str
    issuer: str
    actors: dict[str, Actor]
    judge_model: str
    judge_base_url: str
    judge_api_key: str
    data_dir: Path
    out_dir: Path
    timeout_seconds: float = 120.0
    reply_timeout_seconds: float = 600.0
    search_page_size: int = 20
    recall_at: tuple[int, ...] = field(default=(5, 10))

    @staticmethod
    def from_environment() -> Config:
        labels = [entry.strip() for entry in _required("MEMORYOS_BENCHMARK_ACTORS").split(",")]
        if any(not label for label in labels):
            raise ConfigError(
                "MEMORYOS_BENCHMARK_ACTORS is a comma-separated list of labels, "
                'e.g. "authorized,unauthorized"'
            )
        root = Path(__file__).resolve().parent.parent
        return Config(
            base_url=_required("MEMORYOS_BASE_URL").rstrip("/"),
            issuer=_required("MEMORYOS_ISSUER").rstrip("/"),
            actors={label: Actor.from_environment(label) for label in labels},
            judge_model=os.environ.get("MEMORYOS_JUDGE_MODEL", "qwen/qwen3.8-27b").strip(),
            judge_base_url=os.environ.get(
                "MEMORYOS_JUDGE_BASE_URL", "https://openrouter.ai/api/v1"
            ).rstrip("/"),
            judge_api_key=os.environ.get("MEMORYOS_JUDGE_API_KEY", "").strip(),
            data_dir=Path(os.environ.get("MEMORYOS_BENCHMARK_DATA", root / "datasets")),
            out_dir=Path(os.environ.get("MEMORYOS_BENCHMARK_OUT", root / "runs")),
        )
