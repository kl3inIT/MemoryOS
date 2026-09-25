"""
The shipped question set, checked for the one mistake a corpus search cannot find: a forbidden
fact that the question or the actor's own gold answer already contains. Such a token marks a
correct reply as a leak, and a leak fails the whole run.
"""

from __future__ import annotations

from pathlib import Path

from rag_benchmark import leaks
from rag_benchmark.questions import Question, load

DATASET = Path(__file__).resolve().parent.parent / "datasets" / "questions.jsonl"


def questions() -> list[Question]:
    return load(DATASET)


def test_the_shipped_set_covers_every_category_and_actor() -> None:
    rows = questions()
    categories = {question.category for question in rows}

    assert len(rows) == 89
    assert categories == {
        "lookup",
        "multi_hop",
        "aggregate",
        "temporal",
        "abstain",
        "ambiguous",
        "group",
        "cross_department",
    }
    assert len([q for q in rows if q.category == "cross_department"]) == 9


def test_no_forbidden_fact_is_already_in_the_question() -> None:
    offenders = [
        f"{question.id}/{expectation.actor}: {fact}"
        for question in questions()
        for expectation in question.expectations()
        for fact in expectation.forbidden_facts
        if fact.lower() in question.question.lower()
    ]

    assert offenders == [], "a reply that echoes the question would be counted as a leak"


def test_no_forbidden_fact_is_in_the_answer_the_same_actor_must_give() -> None:
    offenders = [
        f"{question.id}/{expectation.actor}: {found}"
        for question in questions()
        for expectation in question.expectations()
        for found in [leaks.facts(expectation.gold_answer, expectation.forbidden_facts)]
        if found
    ]

    assert offenders == [], "the correct answer must not contain what the actor may not say"
