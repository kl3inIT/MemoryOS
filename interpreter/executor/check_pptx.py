#!/opt/executor-venv/bin/python
"""Report the slide faults that make a generated .pptx unusable (MEM-122).

The gate for any presentation, including one a model built by hand with python-pptx instead of writing a
deck plan for ``render-deck``: overflowing text, empty slides, no readable title, sub-10 pt text, leftover
placeholder text, over-long lines, shapes off the slide and stretched pictures. As ``check-docx`` does, the
faults are named concretely and nothing is modified.

Usage: check-pptx FILE.pptx [FILE.pptx ...]
Prints one JSON object per file:
{"file", "checked", "slides", "built_with_render_deck", "issue_count", "issues"}.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from memoryos_deck import inspect as deck_inspect


def _report(path: Path) -> dict[str, object]:
    if path.suffix.lower() != ".pptx":
        return {"file": path.name, "checked": False, "error": "only .pptx files are supported"}
    if not path.is_file():
        return {"file": path.name, "checked": False, "error": "file not found"}
    return deck_inspect.report(str(path), path.name)


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: check-pptx FILE.pptx [FILE.pptx ...]", file=sys.stderr)
        return 2
    reports = [_report(Path(argument)) for argument in argv]
    for report in reports:
        print(json.dumps(report, ensure_ascii=False))
    # A fault is a report, not a failed command: the model reads the JSON and decides what to fix.
    return 0 if all(report.get("checked") for report in reports) else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
