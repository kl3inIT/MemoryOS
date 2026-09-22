"""
The frozen corpus. What the benchmark actor can read is the corpus, so the manifest is built
from the API, never from the database: a document nobody can retrieve is not measured.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .client import ActorClient

# One broad query per common word is enough to enumerate a staging-sized corpus through the search
# page.
PROBES = (
    "báo cáo",
    "tài chính",
    "hợp đồng",
    "nhân sự",
    "doanh thu",
    "quy định",
    "kế hoạch",
    "2025",
    "2026",
    # Governance and legal filings: a corpus read by department misses them without these.
    "nghị quyết",
    "điều lệ",
    "hội đồng quản trị",
    "đại hội đồng cổ đông",
    "kiểm toán",
    "tờ trình",
    "thông báo",
    "quy chế",
    # English filings sit beside their Vietnamese originals.
    "report",
    "proposal",
    "board of directors",
    "general meeting of shareholders",
    "regulation",
    "form",
)


@dataclass(frozen=True)
class Entry:
    document_id: str
    generation: str
    title: str
    media_type: str | None
    source_types: list[str]


def freeze(
    client: ActorClient, path: Path, probes: tuple[str, ...] = PROBES, limit: int = 200
) -> list[Entry]:
    """Collects every document the actor sees across the probe queries and writes the manifest."""
    found: dict[str, Entry] = {}
    for probe in probes:
        for result in client.search(probe, limit):
            entry = _entry(result)
            found.setdefault(entry.document_id, entry)
    entries = sorted(found.values(), key=lambda item: item.title.lower())
    write(path, entries)
    return entries


def complete(
    client: ActorClient, path: Path, entries: list[Entry], candidates: list[Entry]
) -> list[Entry]:
    """
    Adds the candidates the probes missed but the actor may read, asked of the server one by one.
    A probe returns at most the retrieval budget of chunks, so large documents can crowd small ones
    out of every probe; the reader check does not depend on ranking.
    """
    found = {entry.document_id: entry for entry in entries}
    for candidate in candidates:
        if candidate.document_id not in found and client.readable(
            candidate.document_id, candidate.generation
        ):
            found[candidate.document_id] = candidate
    completed = sorted(found.values(), key=lambda item: item.title.lower())
    write(path, completed)
    return completed


def actor_path(data_dir: Path, label: str) -> Path:
    """The corpus one actor reads, which the authority checks compare its results against."""
    return data_dir / f"corpus.{label}.json"


def write(path: Path, entries: list[Entry]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps([asdict(entry) for entry in entries], ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def union(manifests: list[list[Entry]]) -> list[Entry]:
    """Every document some actor reads; gold documents must come from here."""
    found: dict[str, Entry] = {}
    for entries in manifests:
        for entry in entries:
            found.setdefault(entry.document_id, entry)
    return sorted(found.values(), key=lambda item: item.title.lower())


def load(path: Path) -> list[Entry]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    return [Entry(**row) for row in rows]


def drift(frozen: list[Entry], current: list[Entry]) -> list[str]:
    """
    Names what changed since the manifest was frozen; a comparison across a changed corpus is not a
    comparison.
    """
    before = {entry.document_id: entry for entry in frozen}
    after = {entry.document_id: entry for entry in current}
    changes = [f"removed: {before[id].title}" for id in before.keys() - after.keys()]
    changes += [f"added: {after[id].title}" for id in after.keys() - before.keys()]
    changes += [
        f"reindexed: {after[id].title}"
        for id in before.keys() & after.keys()
        if before[id].generation != after[id].generation
    ]
    return sorted(changes)


def _entry(result: dict[str, Any]) -> Entry:
    sources = result.get("sourceTypes") or result.get("origins") or []
    return Entry(
        document_id=str(result["documentId"]),
        generation=str(result["generation"]),
        title=str(result.get("title", "")),
        media_type=result.get("mediaType"),
        source_types=sorted({str(source) for source in sources if isinstance(source, str)}),
    )
