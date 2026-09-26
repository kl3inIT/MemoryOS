"""
The question set: one JSON object per line, validated when it is loaded so a typo fails before a
run starts.

A question is asked by one or more actors. The short form names one `actor` and, for a `group`
question, the `denied_actor` that must not reach the gold documents. The long form, required for a
`cross_department` question, gives an `expect` object with one expectation per actor, because the
same question has a different right answer for each department.
"""

from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path

from . import leaks

CATEGORIES = frozenset(
    {
        "lookup",
        "multi_hop",
        "aggregate",
        "temporal",
        "abstain",
        "ambiguous",
        "group",
        "cross_department",
        # No Tenant document answers these; a grounded reply declines instead of answering from
        # the model's own knowledge.
        "general_knowledge",
        # Politics, leaders and religion, which a Tenant can block as sensitive topics.
        "sensitive",
    }
)

# Categories whose every question must be declined, whatever the actor reads.
ABSTAINING_CATEGORIES = frozenset({"abstain", "general_knowledge", "sensitive"})


class QuestionError(ValueError):
    """The question file cannot be trusted; the message names the line and what is wrong with it."""


@dataclass(frozen=True)
class Expectation:
    """
    What one actor must get from a question.

    `partial` marks an actor that may read only part of the evidence: the reply must answer that
    part and say the rest is unavailable instead of filling it from general knowledge. `scored` is
    false for the denied side of a `group` question, which is checked for leaks only.
    """

    actor: str
    gold_document_ids: tuple[str, ...] = ()
    gold_answer: str = ""
    expect_abstain: bool = False
    partial: bool = False
    forbidden_document_ids: tuple[str, ...] = ()
    forbidden_facts: tuple[str, ...] = ()
    scored: bool = True


@dataclass(frozen=True)
class Question:
    id: str
    question: str
    category: str
    actor: str = ""
    gold_document_ids: tuple[str, ...] = ()
    gold_answer: str = ""
    expect_abstain: bool = False
    # A group question also names the actor that must not reach the gold documents.
    denied_actor: str | None = None
    as_of: str | None = None
    notes: str = ""
    tags: tuple[str, ...] = field(default=())
    expect: tuple[Expectation, ...] = ()

    def expectations(self) -> tuple[Expectation, ...]:
        """Every actor this question is asked as, with what that actor must and must not get."""
        if self.expect:
            return self.expect
        main = Expectation(
            actor=self.actor,
            gold_document_ids=self.gold_document_ids,
            gold_answer=self.gold_answer,
            expect_abstain=self.expect_abstain,
        )
        if not self.denied_actor:
            return (main,)
        denied = Expectation(
            actor=self.denied_actor,
            forbidden_document_ids=self.gold_document_ids,
            # The figures of the answer are what a denied reply would leak without citing anything.
            forbidden_facts=leaks.figures(self.gold_answer),
            scored=False,
        )
        return (main, denied)

    @staticmethod
    def from_json(row: dict[str, object], line: int) -> Question:
        def text(key: str, required: bool = True) -> str:
            value = row.get(key, "")
            if not isinstance(value, str) or (required and not value.strip()):
                raise QuestionError(f"line {line}: {key} must be a non-empty string")
            return value.strip()

        category = text("category")
        if category not in CATEGORIES:
            raise QuestionError(
                f"line {line}: category {category!r} is not one of {sorted(CATEGORIES)}"
            )
        identity = text("id")
        asked = text("question")
        raw_as_of = row.get("as_of")
        as_of = raw_as_of if isinstance(raw_as_of, str) else None
        notes = text("notes", required=False)
        tags = _ids(row, "tags", line) if "tags" in row else ()
        if "expect" in row:
            return Question(
                id=identity,
                question=asked,
                category=category,
                as_of=as_of,
                notes=notes,
                tags=tags,
                expect=_expectations(row["expect"], category, line),
            )
        if category == "cross_department":
            raise QuestionError(f"line {line}: a cross_department question gives expect per actor")

        expect_abstain = bool(row.get("expect_abstain", False))
        if category in ABSTAINING_CATEGORIES and not expect_abstain:
            raise QuestionError(f"line {line}: a {category} question sets expect_abstain")
        gold = _ids(row, "gold_document_ids", line)
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
            id=identity,
            question=asked,
            category=category,
            as_of=as_of,
            notes=notes,
            tags=tags,
            actor=text("actor"),
            gold_document_ids=gold,
            gold_answer=text(
                "gold_answer", required=not expect_abstain and category != "ambiguous"
            ),
            expect_abstain=expect_abstain,
            denied_actor=denied if isinstance(denied, str) else None,
        )


def _ids(row: dict[str, object], key: str, line: int) -> tuple[str, ...]:
    value = row.get(key, [])
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise QuestionError(f"line {line}: {key} must be a list of strings")
    return tuple(str(item) for item in value)


def _expectations(value: object, category: str, line: int) -> tuple[Expectation, ...]:
    if not isinstance(value, dict) or not value:
        raise QuestionError(f"line {line}: expect maps each actor label to what it must get")
    expectations: list[Expectation] = []
    for actor, raw in value.items():
        where = f"line {line}: expect.{actor}"
        if not isinstance(actor, str) or not actor.strip() or not isinstance(raw, dict):
            raise QuestionError(f"{where} must be an object keyed by an actor label")
        answer = raw.get("gold_answer", "")
        if not isinstance(answer, str):
            raise QuestionError(f"{where}.gold_answer must be a string")
        abstain = bool(raw.get("expect_abstain", False))
        gold = _ids(raw, "gold_document_ids", line)
        forbidden = _ids(raw, "forbidden_document_ids", line)
        facts = _ids(raw, "forbidden_facts", line)
        if category in ABSTAINING_CATEGORIES and not abstain:
            raise QuestionError(
                f"{where}: every actor of a {category} question sets expect_abstain"
            )
        if abstain and gold:
            raise QuestionError(f"{where}: an abstaining actor names no gold document")
        if not abstain and (not gold or not answer.strip()):
            raise QuestionError(f"{where}: an answering actor needs gold documents and an answer")
        if set(gold) & set(forbidden):
            raise QuestionError(f"{where}: a document cannot be both gold and forbidden")
        if any(not fact.strip() for fact in facts):
            raise QuestionError(f"{where}: forbidden_facts holds non-empty strings")
        expectations.append(
            Expectation(
                actor=actor.strip(),
                gold_document_ids=gold,
                gold_answer=answer.strip(),
                expect_abstain=abstain,
                partial=bool(raw.get("partial", False)),
                forbidden_document_ids=forbidden,
                forbidden_facts=facts,
            )
        )
    if category == "cross_department" and not any(
        expectation.forbidden_document_ids for expectation in expectations
    ):
        raise QuestionError(
            f"line {line}: a cross_department question forbids something to at least one actor"
        )
    return tuple(expectations)


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
