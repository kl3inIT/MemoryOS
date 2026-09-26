"""
Retrieval and answer metrics. Document level, as Onyx `tests/regression/search_quality` scores
documents.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Sequence
from math import log2


def recall_at(ranked: Sequence[str], gold: Sequence[str], k: int) -> float:
    """
    Share of gold documents among the first k results. A question with no gold document has no
    recall.
    """
    if not gold:
        return 0.0
    found = {document for document in ranked[:k]} & set(gold)
    return len(found) / len(set(gold))


def ndcg_at(ranked: Sequence[str], gold: Sequence[str], k: int) -> float:
    """Binary-gain nDCG: every gold document counts the same, so only its rank matters."""
    if not gold:
        return 0.0
    wanted = set(gold)
    gain = sum(1 / log2(rank + 2) for rank, document in enumerate(ranked[:k]) if document in wanted)
    ideal = sum(1 / log2(rank + 2) for rank in range(min(len(wanted), k)))
    return gain / ideal if ideal else 0.0


def citation_recall(cited: Sequence[str], gold: Sequence[str]) -> float:
    """Share of gold documents the reply actually cited."""
    if not gold:
        return 0.0
    return len(set(cited) & set(gold)) / len(set(gold))


def citation_precision(cited: Sequence[str], gold: Sequence[str]) -> float:
    """
    Share of cited documents that were gold. A reply that cites nothing has no precision to report.
    """
    if not cited:
        return 0.0
    return len(set(cited) & set(gold)) / len(set(cited))


def abstained(
    content: str,
    cited: Sequence[str],
    forbidden: Sequence[str] = (),
    refusal_reason: str | None = None,
) -> bool:
    """
    A reply abstains when it declines and reaches nothing it may not read. Citing a permitted
    document while declining is still an abstention: the question is whether the reply answered
    from evidence it should not have, not whether it cited anything at all.

    The server's `refusalReason` decides when it is set. When it is null the wording decides:
    an older server sends no field, and a current one sends null on every reply that grounded
    mode or a blocked topic did not refuse, including an ordinary model reply that says it found
    nothing.
    """
    if set(cited) & set(forbidden):
        return False
    if refusal_reason:
        return True
    text = " ".join(content.lower().split())
    markers = (
        "không tìm thấy",
        "chưa tìm thấy",
        "không tìm được",
        "không có thông tin",
        "chưa có thông tin",
        "không có tài liệu",
        "chưa có tài liệu",
        "không đủ thông tin",
        "chưa đủ thông tin",
        "không thể xác định",
        "chưa xác định được",
        "i could not find",
        "i couldn't find",
        "no information",
        "not enough information",
        "cannot determine",
    )
    return any(marker in text for marker in markers)


_MARKER = re.compile(r"\[(\d{1,3})\]")


def citation_markers(content: str) -> set[int]:
    """The inline `[n]` markers of a reply, each number once."""
    return {int(number) for number in _MARKER.findall(content)}


def asserted_without_citation(
    content: str, citation_ids: Iterable[int], declined: bool, status: str = "COMPLETED"
) -> bool:
    """
    A finished reply that answers but carries no inline marker naming one of its own sources. A
    marker the sources do not hold proves nothing, as the browser leaves it plain text. A refusal,
    an empty reply and a turn that did not complete assert nothing.
    """
    if declined or status != "COMPLETED" or not content.strip():
        return False
    return not citation_markers(content) & set(citation_ids)


def percentile(values: Sequence[float], share: float) -> float:
    """
    Nearest-rank percentile; a run is tens of questions, so an interpolated one would overstate its
    precision.
    """
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, min(len(ordered), round(share * len(ordered))))
    return ordered[rank - 1]
