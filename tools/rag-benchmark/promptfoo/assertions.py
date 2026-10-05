"""The two checks promptfoo cannot make: forbidden documents, and citations that name a real source."""

from __future__ import annotations

import re


def no_forbidden_document(output: dict, context: dict) -> dict:
    """A document the role may not read, or the question's tempting wrong document, was read or cited."""
    variables = context.get("vars", {})
    forbidden = set(variables.get("forbidden") or []) | set(variables.get("must_not_cite") or [])
    reached = set(output.get("readDocuments") or []) | set(output.get("citedDocuments") or [])
    hit = sorted(forbidden & reached)
    return {
        "pass": not hit,
        "score": 0 if hit else 1,
        "reason": f"reached {', '.join(hit)}" if hit else "no forbidden document reached",
    }


def citations_name_sources(output: dict, context: dict) -> dict:
    """Every inline [n] is one of the reply's own sources."""
    cited = {int(n) for n in re.findall(r"\[(\d+)\]", output.get("answer") or "")}
    known = {s.get("citationId") for s in output.get("sources") or []}
    unknown = sorted(cited - known)
    return {
        "pass": not unknown,
        "score": 0 if unknown else 1,
        "reason": f"[{']['.join(map(str, unknown))}] names no source"
        if unknown
        else "citations match sources",
    }
