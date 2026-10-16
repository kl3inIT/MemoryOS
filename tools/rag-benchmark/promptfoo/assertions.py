"""The two checks promptfoo cannot make: forbidden documents, and citations that name a real source."""

from __future__ import annotations

import json
import re


def _ids(value: object) -> list[str]:
    """A JSON list given as a string, since promptfoo would expand a real list into several tests."""
    if isinstance(value, str):
        return json.loads(value) if value.strip() else []
    return list(value or [])


def no_forbidden_document(output: dict, context: dict) -> dict:
    """An access leak: a document the role may not read was read or cited."""
    variables = context.get("vars", {})
    forbidden = set(_ids(variables.get("forbidden")))
    reached = set(output.get("readDocuments") or []) | set(output.get("citedDocuments") or [])
    hit = sorted(forbidden & reached)
    return {
        "pass": not hit,
        "score": 0 if hit else 1,
        "reason": f"reached {', '.join(hit)}" if hit else "no forbidden document reached",
    }


def no_tempting_citation(output: dict, context: dict) -> dict:
    """The question's tempting wrong document (another period or company) was cited. Reading it is not
    an error; answering from it is."""
    tempting = set(_ids(context.get("vars", {}).get("must_not_cite")))
    hit = sorted(tempting & set(output.get("citedDocuments") or []))
    return {
        "pass": not hit,
        "score": 0 if hit else 1,
        "reason": f"cited {', '.join(hit)}" if hit else "no tempting document cited",
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
