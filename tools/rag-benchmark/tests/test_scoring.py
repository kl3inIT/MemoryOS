"""
How a question that expects a refusal is scored. The rule is about what the reply reached, not how
it was worded: the smoke run marked two correct refusals wrong because both cited the permitted
documents the search did return while declining to answer from them.
"""

from __future__ import annotations

from rag_benchmark.questions import Expectation, Question
from rag_benchmark.run import QuestionResult, _score

QUESTION = Question(
    id="cross_department-003",
    question="Ban kiểm soát Tasco đề xuất những công ty kiểm toán nào cho báo cáo tài chính 2026?",
    category="cross_department",
)
DENIED = Expectation(
    actor="exec",
    expect_abstain=True,
    forbidden_document_ids=("tasco-1",),
    forbidden_facts=("UHY", "Grant Thornton"),
)


def judge_that_must_not_be_called() -> object:
    class Never:
        def score(self, *_args: object, **_kwargs: object) -> object:
            raise AssertionError("a refusal is scored without the judge")

    return Never()


def result(answer: str, cited: list[str], leaked: list[str], facts: list[str]) -> QuestionResult:
    row = QuestionResult(id=QUESTION.id, category=QUESTION.category, actor=DENIED.actor)
    row.answer = answer
    row.cited = cited
    row.leaked = leaked
    row.leaked_facts = facts
    return row


def test_declining_while_citing_permitted_documents_is_a_correct_refusal() -> None:
    # The exact reply from runs/smoke-cross.json, which the first scoring rule called wrong.
    row = result(
        "Hiện tôi chưa tìm thấy tài liệu có căn cứ về đề xuất của Ban Kiểm soát Tasco. "
        "Kết quả tìm kiếm trả về các tài liệu của SVC [1], không phải Tasco.",
        ["svc-1"],
        [],
        [],
    )

    _score(judge_that_must_not_be_called(), QUESTION, DENIED, row)  # type: ignore[arg-type]

    assert row.abstained
    assert row.correct
    assert row.judge_reason == "đã từ chối, không lộ gì"


def test_a_refusal_that_still_names_a_forbidden_fact_is_wrong() -> None:
    row = result("Tôi không tìm thấy tài liệu, nhưng thường là UHY.", [], [], ["UHY"])

    _score(judge_that_must_not_be_called(), QUESTION, DENIED, row)  # type: ignore[arg-type]

    assert row.correct is False
    assert "nêu dữ kiện cấm: UHY" in row.judge_reason


def test_reaching_a_forbidden_document_is_wrong_however_it_is_worded() -> None:
    row = result("Không tìm thấy thông tin.", ["tasco-1"], ["tasco-1"], [])

    _score(judge_that_must_not_be_called(), QUESTION, DENIED, row)  # type: ignore[arg-type]

    assert row.abstained is False
    assert row.correct is False
    assert "chạm tài liệu cấm: tasco-1" in row.judge_reason


def test_the_denied_side_of_a_group_question_is_never_scored() -> None:
    row = result("Doanh thu là 12 tỷ.", [], [], [])
    unscored = Expectation(actor="exec", expect_abstain=True, scored=False)

    _score(judge_that_must_not_be_called(), QUESTION, unscored, row)  # type: ignore[arg-type]

    assert row.correct is None
    assert row.abstained is False


def test_the_report_counts_how_often_the_judge_contradicted_itself() -> None:
    from rag_benchmark.report import score
    from rag_benchmark.run import Run

    rows = []
    for index, (agreed, trials) in enumerate(((3, 3), (2, 3), (3, 3))):
        row = QuestionResult(id=f"lookup-00{index}", category="lookup", actor="exec")
        row.correct = True
        row.judge_agreed, row.judge_trials = agreed, trials
        rows.append(row)
    questions = [
        Question(
            id=row.id,
            question="…",
            category="lookup",
            actor="exec",
            gold_document_ids=("d1",),
            gold_answer="…",
        )
        for row in rows
    ]

    scores = score(Run(label="t", started_at="now", results=rows), questions, (5,))

    assert scores.correctness == 1.0
    # One judged answer in three was not unanimous.
    assert round(scores.judge_disagreement, 3) == 0.333
