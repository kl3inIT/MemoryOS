"""
The question set: one JSON object per line, validated when it is loaded so a typo fails before a
run starts.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path

CATEGORIES = frozenset(
    {"lookup", "multi_hop", "aggregate", "temporal", "abstain", "ambiguous", "group"}
)


class QuestionError(ValueError):
    """The question file cannot be trusted; the message names the line and what is wrong with it."""


@dataclass(frozen=True)
class Question:
    id: str
    question: str
    category: str
    actor: str
    gold_document_ids: tuple[str, ...] = ()
    gold_answer: str = ""
    expect_abstain: bool = False
    # A group question also names the actor that must not reach the gold documents.
    denied_actor: str | None = None
    as_of: str | None = None
    notes: str = ""
    tags: tuple[str, ...] = field(default=())

    @staticmethod
    def from_json(row: dict[str, object], line: int) -> Question:
        def text(key: str, required: bool = True) -> str:
            value = row.get(key, "")
            if not isinstance(value, str) or (required and not value.strip()):
                raise QuestionError(f"line {line}: {key} must be a non-empty string")
            return value.strip()

        def ids(key: str) -> tuple[str, ...]:
            value = row.get(key, [])
            if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
                raise QuestionError(f"line {line}: {key} must be a list of document ids")
            return tuple(str(item) for item in value)

        category = text("category")
        if category not in CATEGORIES:
            raise QuestionError(
                f"line {line}: category {category!r} is not one of {sorted(CATEGORIES)}"
            )
        expect_abstain = bool(row.get("expect_abstain", False))
        gold = ids("gold_document_ids")
        if expect_abstain and gold:
            raise QuestionError(f"line {line}: an abstain question names no gold document")
        if not expect_abstain and category not in {"ambiguous"} and not gold:
            raise QuestionError(
                f"line {line}: {category} questions need at least one gold document"
            )
        denied = row.get("denied_actor")
        if category == "group" and not isinstance(denied, str):
            raise QuestionError(f"line {line}: a group question names denied_actor")
        return Question(
            id=text("id"),
            question=text("question"),
            category=category,
            actor=text("actor"),
            gold_document_ids=gold,
            gold_answer=text(
                "gold_answer", required=not expect_abstain and category != "ambiguous"
            ),
            expect_abstain=expect_abstain,
            denied_actor=denied if isinstance(denied, str) else None,
            as_of=row["as_of"] if isinstance(row.get("as_of"), str) else None,
            notes=text("notes", required=False),
            tags=ids("tags") if "tags" in row else (),
        )


def load(path: Path) -> list[Question]:
    questions = list(_read(path))
    seen: set[str] = set()
    for question in questions:
        if question.id in seen:
            raise QuestionError(f"duplicate question id {question.id!r}")
        seen.add(question.id)
    return questions


def _read(path: Path) -> Iterator[Question]:
    with path.open(encoding="utf-8") as handle:
        for line, raw in enumerate(handle, start=1):
            text = raw.strip()
            if not text or text.startswith("//"):
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as invalid:
                raise QuestionError(f"line {line}: {invalid.msg}") from invalid
            if not isinstance(row, dict):
                raise QuestionError(f"line {line}: each line is one JSON object")
            yield Question.from_json(row, line)
