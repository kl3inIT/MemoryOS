"""What an arm runs with, read from the repository at a git ref (or the working tree) rather than copied here."""

from __future__ import annotations

import subprocess
import textwrap
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[3]
PROMPTS = "core/src/main/java/io/memoryos/chat/prompts/ChatPrompts.java"
SITECUSTOMIZE = "interpreter/executor/sitecustomize.py"
WORKTREE = "WORKTREE"


def read(ref: str, path: str) -> str:
    if ref == WORKTREE:
        return (REPOSITORY / path).read_text(encoding="utf8")
    return subprocess.run(
        ["git", "show", f"{ref}:{path}"], cwd=REPOSITORY, check=True, capture_output=True, encoding="utf8"
    ).stdout


def guidance(java: str) -> str:
    """The run_python guidance text block exactly as ChatPrompts sends it."""
    try:
        block = java.split('RUN_PYTHON_GUIDANCE = """', 1)[1].split('""";', 1)[0]
    except IndexError as error:
        raise ValueError("ChatPrompts.java has no RUN_PYTHON_GUIDANCE text block") from error
    return textwrap.dedent(block).strip()


def arm(ref: str) -> tuple[str, str]:
    """The run_python guidance and the executor sitecustomize at a ref."""
    return guidance(read(ref, PROMPTS)), read(ref, SITECUSTOMIZE)
