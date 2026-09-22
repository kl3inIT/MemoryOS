#!/opt/executor-venv/bin/python
"""Render a deck plan onto the MemoryOS corporate template (MEM-122).

A model writing python-pptx places shapes blind: it cannot see the deck it just wrote, so the geometry is
whatever it guessed — text past the slide edge, a different title size on every slide, fourteen bullets on
one slide. This CLI takes the part a model is good at, the content, and leaves the layout to a fixed
template. Text that does not fit at the smallest size in its ladder fails the render and names the slide,
instead of producing a slide nobody can read.

Usage: render-deck PLAN.json DECK.pptx [DECK.html ...]   (PLAN.json may be "-" to read the plan from stdin)
The output suffix picks the format: `.pptx` is the editable deck, `.html` a self-contained browser deck
that needs no LibreOffice and prints to an identical PDF. Both come from the same plan, the same template
and the same refusals, so naming both writes two views of one document.
Prints one JSON object per output: {"file", "rendered", "slides", "warning_count", "warnings"},
or {"file", "rendered": false, "error_count", "errors"} with exit 1 and no file written.
The slide types and their fields are documented in memoryos_deck/plan.py.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from memoryos_deck import plan as deck_plan
from memoryos_deck import render, web

RENDERERS = {".pptx": render.render, ".html": web.render, ".htm": web.render}


def _load(argument: str) -> object:
    if argument == "-":
        return json.loads(sys.stdin.read())
    return json.loads(Path(argument).read_text(encoding="utf-8"))


def _refused(target: str, errors: list[str]) -> None:
    print(json.dumps({"file": Path(target).name, "rendered": False, "error_count": len(errors),
                      "errors": errors}, ensure_ascii=False))


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: render-deck PLAN.json DECK.pptx [DECK.html ...]", file=sys.stderr)
        return 2
    source, targets = argv[0], argv[1:]
    unknown = [target for target in targets if Path(target).suffix.lower() not in RENDERERS]
    if unknown:
        for target in unknown:
            _refused(target, [f"unsupported output format; use one of {', '.join(sorted(RENDERERS))}"])
        return 1
    try:
        raw = _load(source)
    except (OSError, ValueError) as failure:
        for target in targets:
            _refused(target, [f"the plan could not be read: {type(failure).__name__}: {failure}"[:300]])
        return 1
    try:
        validated = deck_plan.validate(raw)
    except deck_plan.PlanError as refusal:
        for target in targets:
            _refused(target, refusal.errors)
        return 1
    failed = False
    for target in targets:
        try:
            print(json.dumps(RENDERERS[Path(target).suffix.lower()](validated, target), ensure_ascii=False))
        except deck_plan.PlanError as refusal:
            _refused(target, refusal.errors)
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
