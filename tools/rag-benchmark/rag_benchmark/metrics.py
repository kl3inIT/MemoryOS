"""
Retrieval and answer metrics. Document level, as Onyx `tests/regression/search_quality` scores
documents.
"""

from __future__ import annotations

from collections.abc import Sequence
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


def abstained(content: str, cited: Sequence[str]) -> bool:
    """
    A reply abstains when it cites nothing and says so. Citations decide first: a reply that
    cites a document is answering from it, whatever its wording.
    """
    if cited:
        return False
    text = content.lower()
    markers = (
        "không tìm thấy",
        "không có thông tin",
        "không có tài liệu",
        "chưa có thông tin",
        "không đủ thông tin",
        "i could not find",
        "no information",
    )
    return any(marker in text for marker in markers)


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
