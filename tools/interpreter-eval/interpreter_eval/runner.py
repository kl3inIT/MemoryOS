"""Calls the model, runs the script it wrote and scores it.

Model-written code runs on the machine doing the evaluation. `docker` runs it in the executor image with no network
(recommended); `local` runs it with the executor's own virtual environment, in a temporary directory and with an
environment that carries no credentials.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from interpreter_eval.scoring import answer_of, code_of, matches, old_idioms
from interpreter_eval.sources import REPOSITORY

HARNESS = (
    "\n\nThis evaluation cannot call tools. Reply with exactly one ```python code block holding the complete script "
    "you would pass to run_python, and nothing else. The data files are in the current directory."
)
IMAGE_SITECUSTOMIZE = "/opt/executor-venv/lib/python3.14/site-packages/sitecustomize.py"


@dataclass(frozen=True)
class Model:
    base_url: str
    api_key: str
    name: str

    def __post_init__(self) -> None:
        if not self.base_url.startswith(("https://", "http://")):
            raise ValueError("INTERPRETER_EVAL_BASE_URL must be an http(s) URL")

    def chat(self, messages: list[dict[str, str]]) -> str:
        # Reasoning models such as gpt-6-luna accept only their default temperature, so none is sent.
        body = json.dumps({"model": self.name, "stream": False, "messages": messages}).encode()
        for attempt in range(4):
            try:
                request = urllib.request.Request(  # noqa: S310 - the scheme is checked in __post_init__
                    self.base_url.rstrip("/") + "/chat/completions",
                    body,
                    {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"},
                )
                with urllib.request.urlopen(request, timeout=300) as response:  # noqa: S310 - the scheme is checked in __post_init__
                    return json.load(response)["choices"][0]["message"]["content"]
            except Exception:  # noqa: BLE001 - gateways fail transiently; the last failure propagates
                if attempt == 3:
                    raise
                time.sleep(5 * (attempt + 1))
        raise AssertionError("unreachable")


@dataclass(frozen=True)
class Sandbox:
    kind: str  # "docker" or "local"
    image: str | None = None

    def run(self, code: str, sitecustomize: str, fixtures: Path) -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory(prefix="interpreter-eval-") as work:
            for item in fixtures.iterdir():
                shutil.copy(item, work)
            Path(work, "__main__.py").write_text(code, encoding="utf8")
            site = Path(work, ".sitecustomize", "sitecustomize.py")
            site.parent.mkdir()
            site.write_text(sitecustomize, encoding="utf8")
            command, env = self._command(Path(work), site)
            try:
                run = subprocess.run(
                    command,
                    cwd=work,
                    env=env,
                    capture_output=True,
                    text=True,
                    encoding="utf8",
                    errors="replace",
                    timeout=120,
                )
            except subprocess.TimeoutExpired:
                return 124, "", "timeout"
            return run.returncode, run.stdout, run.stderr

    def _command(self, work: Path, site: Path) -> tuple[list[str], dict[str, str]]:
        if self.kind == "docker":
            command = [
                "docker",
                "run",
                "--rm",
                "--network",
                "none",
                "--memory",
                "1g",
                "--cpus",
                "1",
                "-v",
                f"{work}:/workspace",
                "-w",
                "/workspace",
                "-v",
                f"{site}:{IMAGE_SITECUSTOMIZE}:ro",
                str(self.image),
                "python",
                "/workspace/__main__.py",
            ]
            return command, dict(os.environ)
        # Only what Python needs: no credential reaches model-written code.
        env = {name: os.environ[name] for name in ("PATH", "SYSTEMROOT", "TEMP", "TMP") if name in os.environ}
        env.update(
            {
                "PYTHONPATH": str(site.parent),
                "PYTHONIOENCODING": "utf-8",
                "MPLBACKEND": "Agg",
                "MPLCONFIGDIR": str(work / ".matplotlib"),
            }
        )
        return [str(executor_python()), "__main__.py"], env


def executor_python() -> Path:
    venv = REPOSITORY / "interpreter" / "executor" / ".venv"
    python = venv / ("Scripts/python.exe" if sys.platform == "win32" else "bin/python")
    if not python.exists():
        raise SystemExit(f"{python} is missing: run `uv sync --directory interpreter/executor --frozen --no-dev`")
    return python


def trial(
    model: Model,
    sandbox: Sandbox,
    fixtures: Path,
    arm: str,
    guidance: str,
    sitecustomize: str,
    task: str,
    prompt: str,
    expected: object,
) -> dict[str, object]:
    """One task for one arm: the model's script, and when it fails, one fix after reading its traceback."""
    messages = [{"role": "system", "content": guidance + HARNESS}, {"role": "user", "content": prompt}]
    reply = model.chat(messages)
    code = code_of(reply)
    first = _attempt(sandbox, fixtures, code, sitecustomize, expected)
    final = first
    if first["exit"] != 0:
        messages += [
            {"role": "assistant", "content": reply},
            {
                "role": "user",
                "content": f"The run failed with exit code {first['exit']}:\n"
                f"{first.pop('stderr_tail')}\nSend the corrected complete script.",
            },
        ]
        final = _attempt(sandbox, fixtures, code_of(model.chat(messages)), sitecustomize, expected)
    first.pop("stderr_tail", None)
    final.pop("stderr_tail", None)
    return {"arm": arm, "task": task, "first": first, "final": final, "old_idioms": old_idioms(code), "code": code}


def _attempt(sandbox: Sandbox, fixtures: Path, code: str, sitecustomize: str, expected: object) -> dict[str, object]:
    status, stdout, stderr = sandbox.run(code, sitecustomize, fixtures)
    error = stderr.strip().splitlines()[-1][:200] if status and stderr.strip() else ""
    return {
        "exit": status,
        "correct": status == 0 and matches(expected, answer_of(stdout)),
        "error": error,
        "stdout_tail": stdout[-300:],
        "stderr_tail": stderr[-2500:],
    }
