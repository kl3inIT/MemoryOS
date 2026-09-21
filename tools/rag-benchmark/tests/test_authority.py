import json
from pathlib import Path

import pytest

from rag_benchmark import leaks, report
from rag_benchmark.cli import _consistency
from rag_benchmark.config import Actor, Config
from rag_benchmark.corpus import Entry, actor_path, write
from rag_benchmark.questions import QuestionError, load
from rag_benchmark.run import QuestionResult, Run

HROD, LEGAL, FINANCE = "doc-hrod", "doc-legal", "doc-finance"


def write_questions(tmp_path: Path, rows: list[dict[str, object]]) -> Path:
    path = tmp_path / "questions.jsonl"
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8"
    )
    return path


def cross_question() -> dict[str, object]:
    return {
        "id": "cross-001",
        "category": "cross_department",
        "question": "Ai được bổ nhiệm Phó Tổng Giám đốc và ai có thẩm quyền bổ nhiệm?",
        "expect": {
            "exec": {"gold_document_ids": [HROD, LEGAL], "gold_answer": "Ba người; HĐQT bổ nhiệm."},
            "hr": {
                "gold_document_ids": [HROD],
                "gold_answer": "Ba người; không có tài liệu về thẩm quyền.",
                "partial": True,
                "forbidden_document_ids": [LEGAL],
                "forbidden_facts": ["Điều 24.3.2"],
            },
            "outsider": {"expect_abstain": True, "forbidden_document_ids": [HROD, LEGAL]},
        },
    }


# Leak detection ---------------------------------------------------------------------------------


def test_figures_are_the_long_numbers_of_an_answer_and_not_its_years() -> None:
    assert leaks.figures("6.445.238.783.576 đồng (quý 1 năm 2026; 5.547.507.569.647)") == (
        "6.445.238.783.576",
        "5.547.507.569.647",
    )


def test_a_figure_leaks_whatever_separators_the_reply_uses() -> None:
    assert leaks.facts("Doanh thu là 6445238783576 đồng", ["6.445.238.783.576"]) == [
        "6.445.238.783.576"
    ]
    assert leaks.facts("Doanh thu là 6,445,238,783,576", ["6.445.238.783.576"]) == [
        "6.445.238.783.576"
    ]
    assert leaks.facts("Không có thông tin", ["6.445.238.783.576"]) == []


def test_a_text_fact_leaks_regardless_of_case_and_spacing() -> None:
    assert leaks.facts("Theo  điều 24.3.2 của Điều lệ", ["Điều 24.3.2"]) == ["Điều 24.3.2"]


def test_a_forbidden_document_leaks_on_any_surface() -> None:
    seen = {"a", LEGAL} | {"b"}
    assert leaks.documents(seen, [LEGAL, "c"]) == [LEGAL]


# Questions ------------------------------------------------------------------------------------


def test_a_cross_department_question_holds_one_expectation_per_actor(tmp_path: Path) -> None:
    (question,) = load(write_questions(tmp_path, [cross_question()]))
    by_actor = {expectation.actor: expectation for expectation in question.expectations()}

    assert set(by_actor) == {"exec", "hr", "outsider"}
    assert by_actor["hr"].partial
    assert by_actor["hr"].forbidden_document_ids == (LEGAL,)
    assert by_actor["outsider"].expect_abstain


def test_a_group_question_forbids_its_gold_documents_and_figures_to_the_denied_actor(
    tmp_path: Path,
) -> None:
    (question,) = load(
        write_questions(
            tmp_path,
            [
                {
                    "id": "g1",
                    "category": "group",
                    "question": "Doanh thu quý 1?",
                    "actor": "exec",
                    "denied_actor": "outsider",
                    "gold_document_ids": [FINANCE],
                    "gold_answer": "6.445.238.783.576 đồng",
                }
            ],
        )
    )
    exec_side, denied = question.expectations()

    assert exec_side.actor == "exec" and exec_side.scored
    assert denied.actor == "outsider" and not denied.scored
    assert denied.forbidden_document_ids == (FINANCE,)
    assert denied.forbidden_facts == ("6.445.238.783.576",)


@pytest.mark.parametrize(
    ("change", "message"),
    [
        (lambda row: row.pop("expect"), "expect per actor"),
        (
            lambda row: row["expect"]["hr"].update(forbidden_document_ids=[HROD]),
            "both gold and forbidden",
        ),
        (
            lambda row: [
                spec.pop("forbidden_document_ids", None) for spec in row["expect"].values()
            ],
            "forbids something",
        ),
    ],
)
def test_a_cross_department_question_that_measures_nothing_is_rejected(
    tmp_path: Path, change: object, message: str
) -> None:
    row = cross_question()
    change(row)  # type: ignore[operator]
    with pytest.raises(QuestionError, match=message):
        load(write_questions(tmp_path, [row]))


# Report ---------------------------------------------------------------------------------------


def test_leaked_facts_fail_the_run_and_partial_answers_are_scored_apart(tmp_path: Path) -> None:
    questions = load(write_questions(tmp_path, [cross_question()]))
    results = [
        QuestionResult(
            id="cross-001",
            category="cross_department",
            actor="exec",
            retrieved=[HROD, LEGAL],
            cited=[HROD, LEGAL],
            status="COMPLETED",
            correct=True,
            seconds=4.0,
        ),
        QuestionResult(
            id="cross-001",
            category="cross_department",
            actor="hr",
            partial=True,
            retrieved=[HROD],
            cited=[HROD],
            status="COMPLETED",
            correct=False,
            leaked_facts=["Điều 24.3.2"],
            seconds=4.0,
        ),
        QuestionResult(
            id="cross-001",
            category="cross_department",
            actor="outsider",
            status="COMPLETED",
            correct=True,
            abstained=True,
            outside_corpus=["doc-unknown"],
            seconds=2.0,
        ),
    ]

    scores = report.score(Run(label="t", started_at="now", results=results), questions, (5,))

    assert scores.leaks == 1
    assert scores.partial_correctness == 0.0
    assert scores.correctness == 0.5
    assert scores.abstention == 1.0
    assert scores.by_actor["hr"].leaks == 1
    # A document outside the frozen corpus is shown for reading; it does not fail the gate.
    assert scores.outside_corpus == 1
    assert report.regressions(scores, None) == [
        "rò rỉ quyền: 1 tài liệu hoặc dữ kiện không được phép đã tới actor"
    ]


# Consistency with the Group configuration ----------------------------------------------------


def config_for(tmp_path: Path) -> Config:
    return Config(
        base_url="https://memoryos.test",
        issuer="https://auth.test/realms/memoryos",
        actors={label: Actor(label=label) for label in ("exec", "hr", "outsider")},
        judge_model="judge",
        judge_base_url="https://judge.test",
        judge_api_key="",
        judge_trials=1,
        data_dir=tmp_path,
        out_dir=tmp_path / "runs",
        token_store=tmp_path / "tokens.json",
    )


def entry(document_id: str) -> Entry:
    return Entry(
        document_id=document_id, generation="g", title=document_id, media_type=None, source_types=[]
    )


def test_check_names_a_question_that_disagrees_with_what_each_actor_reads(tmp_path: Path) -> None:
    config = config_for(tmp_path)
    write(tmp_path / "corpus.json", [entry(HROD), entry(LEGAL)])
    write(actor_path(tmp_path, "exec"), [entry(HROD), entry(LEGAL)])
    # The HR Group was also given the Legal Source: the question's expectation no longer holds.
    write(actor_path(tmp_path, "hr"), [entry(HROD), entry(LEGAL)])
    write(actor_path(tmp_path, "outsider"), [])
    questions = load(write_questions(tmp_path, [cross_question()]))

    assert _consistency(questions, config) == [
        f"cross-001 [hr]: this actor can read forbidden document {LEGAL}"
    ]


def test_a_run_whose_asks_failed_is_not_a_pass(tmp_path: Path) -> None:
    questions = load(write_questions(tmp_path, [cross_question()]))
    broken = [
        QuestionResult(
            id="cross-001",
            category="cross_department",
            actor=actor,
            error="session creation failed (400)",
        )
        for actor in ("exec", "hr", "outsider")
    ]

    scores = report.score(Run(label="t", started_at="now", results=broken), questions, (5,))

    assert scores.errors == 3
    assert report.regressions(scores, None) == ["lỗi khi chạy: 3 lượt hỏi không hoàn thành"]
