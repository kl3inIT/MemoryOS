"""What an arm runs with, read from the repository at a git ref (or the working tree) rather than copied here."""

from __future__ import annotations

import subprocess
import textwrap
from dataclasses import dataclass
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[3]
PROMPTS = "memoryos-core/src/main/java/io/memoryos/chat/prompts/ChatPrompts.java"
WORKTREE = "WORKTREE"


@dataclass(frozen=True)
class Runtime:
    """Where run_python runs: its sitecustomize in the repository and in its image, and where it finds the files."""

    sitecustomize: tuple[str, ...]  # its paths in the repository, newest first; a ref has one of them
    image_sitecustomize: str
    data: str  # the conversation's files, relative to the working directory


RUNTIMES = {
    # Retired by MEM-271; its source moved from interpreter/ to memoryos-interpreter/.
    "interpreter": Runtime(
        ("memoryos-interpreter/executor/sitecustomize.py", "interpreter/executor/sitecustomize.py"),
        "/opt/executor-venv/lib/python3.14/site-packages/sitecustomize.py",
        ".",
    ),
    # MEM-271: the conversation's sandbox, with the attachments under attachments/.
    "sandbox": Runtime(
        ("memoryos-sandbox/computer/sitecustomize.py",),
        "/opt/sandbox-venv/lib/python3.14/site-packages/sitecustomize.py",
        "attachments",
    ),
}


def read(ref: str, path: str) -> str:
    if ref == WORKTREE:
        return (REPOSITORY / path).read_text(encoding="utf8")
    return subprocess.run(
        ["git", "show", f"{ref}:{path}"], cwd=REPOSITORY, check=True, capture_output=True, encoding="utf8"
    ).stdout


def sitecustomize(ref: str, runtime: str) -> str:
    """The runtime's sitecustomize at a ref, from the first of its paths the ref has."""
    paths = RUNTIMES[runtime].sitecustomize
    for path in paths[:-1]:
        try:
            return read(ref, path)
        except (FileNotFoundError, subprocess.CalledProcessError):
            continue
    return read(ref, paths[-1])


def guidance(java: str) -> str:
    """The run_python guidance text block exactly as ChatPrompts sends it."""
    try:
        block = java.split('RUN_PYTHON_GUIDANCE = """', 1)[1].split('""";', 1)[0]
    except IndexError as error:
        raise ValueError("ChatPrompts.java has no RUN_PYTHON_GUIDANCE text block") from error
    return textwrap.dedent(block).strip()


def arm(ref: str, runtime: str = "sandbox") -> tuple[str, str]:
    """The run_python guidance and the runtime's sitecustomize at a ref."""
    return guidance(read(ref, PROMPTS)), sitecustomize(ref, runtime)
