import json
from pathlib import Path

import pytest

from rag_benchmark import report
from rag_benchmark.questions import Question, QuestionError, load
from rag_benchmark.run import QuestionResult, Run


def write(tmp_path: Path, rows: list[dict[str, object]]) -> Path:
    path = tmp_path / "questions.jsonl"
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8"
    )
    return path


def test_a_question_file_is_rejected_before_a_run_when_it_cannot_be_scored(tmp_path: Path) -> None:
    with pytest.raises(QuestionError, match="gold document"):
        load(
            write(
                tmp_path,
                [{"id": "q1", "question": "Doanh thu?", "category": "lookup", "actor": "a"}],
            )
        )
    with pytest.raises(QuestionError, match="denied_actor"):
        load(
            write(
                tmp_path,
                [
                    {
                        "id": "q2",
                        "question": "Chi phí?",
                        "category": "group",
                        "actor": "a",
                        "gold_document_ids": ["d1"],
                        "gold_answer": "12",
                    }
                ],
            )
        )
    with pytest.raises(QuestionError, match="duplicate"):
        row = {
            "id": "q3",
            "question": "X?",
            "category": "abstain",
            "actor": "a",
            "expect_abstain": True,
        }
        load(write(tmp_path, [row, row]))


def test_abstain_and_group_questions_load_with_their_own_rules(tmp_path: Path) -> None:
    questions = load(
        write(
            tmp_path,
            [
                {
                    "id": "a1",
                    "question": "Thưởng 2030?",
                    "category": "abstain",
                    "actor": "a",
                    "expect_abstain": True,
                },
                {
                    "id": "g1",
                    "question": "Chi phí?",
                    "category": "group",
                    "actor": "a",
                    "denied_actor": "b",
                    "gold_document_ids": ["d1"],
                    "gold_answer": "12 tỷ",
                },
            ],
        )
    )
    assert [question.id for question in questions] == ["a1", "g1"]
    assert questions[1].denied_actor == "b"


def scored(results: list[QuestionResult], questions: list[Question]) -> report.Scores:
    return report.score(Run(label="t", started_at="now", results=results), questions, (5,))


def test_a_leak_fails_the_run_whatever_the_other_metrics_say() -> None:
    question = Question(
        id="g1",
        question="Chi phí?",
        category="group",
        actor="a",
        denied_actor="b",
        gold_document_ids=("d1",),
        gold_answer="12 tỷ",
    )
    result = QuestionResult(
        id="g1",
        category="group",
        actor="a",
        retrieved=["d1"],
        cited=["d1"],
        status="COMPLETED",
        correct=True,
        leaked=["d1"],
        seconds=3.0,
    )
    scores = scored([result], [question])

    assert scores.leaks == 1
    assert scores.recall[5] == 1.0
    assert report.regressions(scores, None) == [
        "rò rỉ quyền: 1 tài liệu hoặc dữ kiện không được phép đã tới actor"
    ]


def test_a_candidate_is_rejected_only_when_a_metric_falls_below_the_baseline(
    tmp_path: Path,
) -> None:
    question = Question(
        id="l1",
        question="Doanh thu?",
        category="lookup",
        actor="a",
        gold_document_ids=("d1",),
        gold_answer="12 tỷ",
    )
    weaker = QuestionResult(
        id="l1",
        category="lookup",
        actor="a",
        retrieved=["x", "y"],
        cited=[],
        status="COMPLETED",
        correct=False,
        seconds=2.0,
    )
    scores = scored([weaker], [question])
    baseline_path = tmp_path / "baseline.json"
    report.save_baseline(
        baseline_path,
        scored(
            [
                QuestionResult(
                    id="l1",
                    category="lookup",
                    actor="a",
                    retrieved=["d1"],
                    cited=["d1"],
                    status="COMPLETED",
                    correct=True,
                    seconds=2.0,
                )
            ],
            [question],
        ),
    )
    baseline = report.load_baseline(baseline_path)

    failures = report.regressions(scores, baseline)

    assert baseline is not None
    assert any("nDCG@5" in failure for failure in failures)
    assert any("trả lời đúng" in failure for failure in failures)
    # The same run against itself is acceptable, so an unchanged candidate never fails the gate.
    assert report.regressions(baseline, baseline) == []
