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


def _whole(name: str, fallback: int) -> int:
    """A malformed number is a configuration mistake; `cli.main` reports those instead of
    letting a ValueError escape."""
    value = os.environ.get(name, "").strip()
    if not value:
        return fallback
    try:
        return int(value)
    except ValueError as broken:
        raise ConfigError(f"{name} must be a whole number, not {value!r}") from broken


def _suffix(label: str) -> str:
    return label.upper().replace("-", "_")


@dataclass(frozen=True)
class Actor:
    """
    One identity the benchmark asks as; questions refer to it by label.

    The realm enables neither direct access grants nor implicit flows, so a password is never a
    credential here. An actor authenticates, in order of preference, through a refresh token stored
    by `rag-benchmark login`, its own service-account client (client credentials) whose subject is
    bound to the Actor, or a bearer token captured for one short run.
    """

    label: str
    client_id: str | None = None
    client_secret: str | None = None
    token: str | None = None

    @property
    def has_environment_credential(self) -> bool:
        return bool(self.token or (self.client_id and self.client_secret))

    @staticmethod
    def from_environment(label: str) -> Actor:
        """Reads the optional environment credential; a stored login is checked at run start."""
        suffix = _suffix(label)
        token = os.environ.get(f"MEMORYOS_BENCHMARK_TOKEN_{suffix}", "").strip()
        client_id = os.environ.get(f"MEMORYOS_BENCHMARK_CLIENT_{suffix}", "").strip()
        client_secret = os.environ.get(f"MEMORYOS_BENCHMARK_SECRET_{suffix}", "").strip()
        if bool(client_id) != bool(client_secret):
            raise ConfigError(
                f"Set both MEMORYOS_BENCHMARK_CLIENT_{suffix} and "
                f"MEMORYOS_BENCHMARK_SECRET_{suffix}"
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
    # How many times each answer is judged. A single verdict from a model that is not deterministic
    # is a coin toss on the borderline answers; the majority of three is the recorded score.
    judge_trials: int
    data_dir: Path
    out_dir: Path
    token_store: Path
    login_client_id: str = "memoryos-integration"
    login_port: int = 8765
    timeout_seconds: float = 120.0
    reply_timeout_seconds: float = 600.0
    search_page_size: int = 20
    recall_at: tuple[int, ...] = field(default=(5, 10))
    # The model configuration that answers, or None for the Tenant default. The turn endpoint
    # one per message, so comparing two models changes nothing for the Tenant.
    model_configuration_id: str | None = None
    # Sent with every judge request unless None: reasoning models (OpenAI gpt-6-luna) accept
    # only the default temperature and refuse any other value with 400.
    judge_temperature: float | None = 0.0

    @staticmethod
    def from_environment() -> Config:
        labels = [entry.strip() for entry in _required("MEMORYOS_BENCHMARK_ACTORS").split(",")]
        if any(not label for label in labels):
            raise ConfigError(
                "MEMORYOS_BENCHMARK_ACTORS is a comma-separated list of labels, "
                'e.g. "exec,finance,all-departments,outsider"'
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
            judge_trials=max(1, _whole("MEMORYOS_JUDGE_TRIALS", 3)),
            judge_temperature=_temperature(os.environ.get("MEMORYOS_JUDGE_TEMPERATURE", "0")),
            model_configuration_id=os.environ.get("MEMORYOS_BENCHMARK_MODEL", "").strip() or None,
            data_dir=Path(os.environ.get("MEMORYOS_BENCHMARK_DATA", root / "datasets")),
            out_dir=Path(os.environ.get("MEMORYOS_BENCHMARK_OUT", root / "runs")),
            # Outside the repository: a refresh token is a credential.
            token_store=Path(
                os.environ.get(
                    "MEMORYOS_BENCHMARK_TOKENS",
                    Path.home() / ".memoryos-rag-benchmark" / "tokens.json",
                )
            ),
            login_client_id=os.environ.get(
                "MEMORYOS_BENCHMARK_LOGIN_CLIENT", "memoryos-integration"
            ).strip(),
            login_port=_whole("MEMORYOS_BENCHMARK_LOGIN_PORT", 8765),
        )


def _temperature(value: str) -> float | None:
    """Empty sends no temperature, for judge models that only accept their default."""
    text = value.strip()
    if not text:
        return None
    try:
        return float(text)
    except ValueError as error:
        raise ConfigError(
            f"MEMORYOS_JUDGE_TEMPERATURE must be a number or empty, got {value!r}"
        ) from error
