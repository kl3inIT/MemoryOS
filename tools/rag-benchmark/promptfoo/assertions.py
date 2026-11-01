"""The checks promptfoo cannot make: forbidden documents, citations that name a real source, and which
documents the model read against the gold documents."""

from __future__ import annotations

import json
import math
import re
import unicodedata


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


# Context recall and precision measure, they do not judge: both always pass so that adding them leaves the
# baseline's row pass rate unchanged, and the score carries the measure.
def context_recall(output: dict, context: dict) -> dict:
    """Share of the gold documents the role may read that are among the documents the model read."""
    gold = set(_ids(context.get("vars", {}).get("gold")))
    read = set(output.get("readDocuments") or [])
    found = gold & read
    return {
        "pass": True,
        "score": len(found) / len(gold) if gold else 0,
        "reason": f"read {len(found)}/{len(gold)} gold documents"
        + (f"; missed {', '.join(sorted(gold - read))}" if gold - read else ""),
    }


def context_precision(output: dict, context: dict) -> dict:
    """Share of the documents the model read that are gold documents; 0 when it read none."""
    gold = set(_ids(context.get("vars", {}).get("gold")))
    read = set(output.get("readDocuments") or [])
    return {
        "pass": True,
        "score": len(gold & read) / len(read) if read else 0,
        "reason": f"{len(gold & read)} of {len(read)} documents read are gold",
    }


# Retrieval stage (MEM-230 P9b). Like context recall, these measure and always pass; the score carries
# the measure. Relevance is binary: a gold document, or a section holding an evidence quote.
GRAM = 6


def _compact(text: str) -> str:
    """Letters and digits only: extraction splits words and figures ("M inh", "46.621.079. 163")."""
    return "".join(re.findall(r"[^\W_]+", unicodedata.normalize("NFC", text).lower()))


def _grams(text: str) -> set[str]:
    return {text[i : i + GRAM] for i in range(len(text) - GRAM + 1)}


def quote_found(quote: str, text: str) -> bool:
    """At least 90% of the quote's character 6-grams occur in the text, counted per fragment: a quote
    joins the fragments it needs with " | ", and a table quote taken from a PDF's text layer puts each
    cell on its own line, while the index holds cells in its own table order. The share tolerates a few
    misread characters."""
    present, compact = _grams(_compact(text)), _compact(text)
    grams: set[str] = set()
    for fragment in (_compact(f) for f in re.split(r" \| |\n", quote)):
        if 0 < len(fragment) < GRAM and fragment not in compact:
            return False
        grams |= _grams(fragment)
    return not grams or len(grams & present) >= 0.9 * len(grams)


def _measure(score: float, reason: str) -> dict:
    return {"pass": True, "score": score, "reason": reason}


def _ranks(output: dict, gold: set[str]) -> list[int]:
    """1-based ranks of the gold documents in the result list."""
    return [i for i, r in enumerate(output.get("ranked") or [], 1) if r["document"] in gold]


def extraction_coverage(output: dict, context: dict) -> dict:
    """Share of the evidence quotes found in their document's indexed text."""
    quotes = _ids(context.get("vars", {}).get("quotes"))
    texts = output.get("goldText") or {}
    found = [q for q in quotes if quote_found(q["quote"], texts.get(q["document"], ""))]
    return _measure(len(found) / len(quotes), f"{len(found)}/{len(quotes)} quotes in the index")


def _recall_at(k: int) -> object:
    def recall(output: dict, context: dict) -> dict:
        gold = set(_ids(context.get("vars", {}).get("gold")))
        hit = [r for r in _ranks(output, gold) if r <= k]
        return _measure(len(hit) / len(gold), f"{len(hit)}/{len(gold)} gold documents in top {k}")

    return recall


recall_at_10 = _recall_at(10)
recall_at_50 = _recall_at(50)


def ndcg_at_10(output: dict, context: dict) -> dict:
    gold = set(_ids(context.get("vars", {}).get("gold")))
    ranks = [r for r in _ranks(output, gold) if r <= 10]
    dcg = sum(1 / math.log2(r + 1) for r in ranks)
    ideal = sum(1 / math.log2(r + 1) for r in range(1, min(len(gold), 10) + 1))
    return _measure(dcg / ideal, f"gold at ranks {ranks or 'none'} of 10")


def mrr_at_10(output: dict, context: dict) -> dict:
    ranks = [r for r in _ranks(output, set(_ids(context.get("vars", {}).get("gold")))) if r <= 10]
    return _measure(1 / ranks[0] if ranks else 0, f"first gold at {ranks[0] if ranks else 'none'}")


def passage_recall_at_10(output: dict, context: dict) -> dict:
    """Share of the evidence quotes found in the sections returned with the top 10 documents."""
    quotes = _ids(context.get("vars", {}).get("quotes"))
    sections = {}
    for r in (output.get("ranked") or [])[:10]:
        sections.setdefault(r["document"], []).extend(r["sections"])
    # One quote can span sections (a table cell and its headers), so a document's sections count together.
    found = [
        q for q in quotes if quote_found(q["quote"], " ".join(sections.get(q["document"], [])))
    ]
    return _measure(
        len(found) / len(quotes), f"{len(found)}/{len(quotes)} quotes in top-10 sections"
    )


def tempting_first(output: dict, context: dict) -> dict:
    """1 when the question's tempting document (another period or company) ranks above every gold one."""
    variables = context.get("vars", {})
    tempting = _ranks(output, set(_ids(variables.get("tempting"))))
    gold = _ranks(output, set(_ids(variables.get("gold"))))
    first = bool(tempting) and (not gold or tempting[0] < gold[0])
    return _measure(
        1 if first else 0, f"tempting at {tempting or 'none'}, gold at {gold or 'none'}"
    )
