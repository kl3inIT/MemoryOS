"""Scores a run, compares it with a baseline and decides whether the candidate is acceptable."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from . import metrics
from .questions import Expectation, Question
from .run import QuestionResult, Run


@dataclass(frozen=True)
class ActorScores:
    asked: int
    # Share of judged asks that were right; judged is zero on a retrieval-only run.
    correct: float
    leaks: int
    judged: int = 0


@dataclass(frozen=True)
class CategoryScores:
    asked: int
    # Share of Chat replies that declined; zero on a retrieval-only run.
    abstention: float
    asserted_uncited: int


@dataclass(frozen=True)
class Scores:
    questions: int
    answered: int
    recall: dict[int, float]
    ndcg5: float
    citation_recall: float
    citation_precision: float
    correctness: float
    abstention: float
    leaks: int
    median_seconds: float
    p90_seconds: float
    errors: int
    # Answers from an actor that may read only part of the evidence.
    partial_correctness: float = 0.0
    # Documents seen outside the actor's frozen corpus: read by hand, not a gate.
    outside_corpus: int = 0
    # Share of judged answers where the judge's trials did not agree. It measures the measurement,
    # not the system: a high rate means the correctness figure above is soft.
    judge_disagreement: float = 0.0
    # Replies that answered with no inline citation naming one of their sources. A leak of the
    # model's own knowledge: grounded mode must keep it at zero.
    asserted_uncited: int = 0
    # Share of answerable asks that were declined; read as the difference between grounded on
    # and off.
    false_refusal: float = 0.0
    # Sensitive questions that were answered instead of declined.
    sensitive_answered: int = 0
    by_actor: dict[str, ActorScores] = field(default_factory=dict)
    by_category: dict[str, CategoryScores] = field(default_factory=dict)

    def as_json(self) -> dict[str, object]:
        return {
            "questions": self.questions,
            "answered": self.answered,
            "recall": {str(k): round(v, 4) for k, v in self.recall.items()},
            "ndcg5": round(self.ndcg5, 4),
            "citationRecall": round(self.citation_recall, 4),
            "citationPrecision": round(self.citation_precision, 4),
            "correctness": round(self.correctness, 4),
            "partialCorrectness": round(self.partial_correctness, 4),
            "abstention": round(self.abstention, 4),
            "leaks": self.leaks,
            "outsideCorpus": self.outside_corpus,
            "medianSeconds": round(self.median_seconds, 2),
            "p90Seconds": round(self.p90_seconds, 2),
            "errors": self.errors,
            "judgeDisagreement": round(self.judge_disagreement, 4),
            "assertedUncited": self.asserted_uncited,
            "falseRefusal": round(self.false_refusal, 4),
            "sensitiveAnswered": self.sensitive_answered,
            "byCategory": {
                category: {
                    "asked": row.asked,
                    "abstention": round(row.abstention, 4),
                    "assertedUncited": row.asserted_uncited,
                }
                for category, row in sorted(self.by_category.items())
            },
            "byActor": {
                label: {
                    "asked": row.asked,
                    "judged": row.judged,
                    "correct": round(row.correct, 4) if row.judged else None,
                    "leaks": row.leaks,
                }
                for label, row in sorted(self.by_actor.items())
            },
        }


def expectation_of(questions: list[Question]) -> dict[tuple[str, str], Expectation]:
    return {
        (question.id, expectation.actor): expectation
        for question in questions
        for expectation in question.expectations()
    }


def score(run: Run, questions: list[Question], recall_at: tuple[int, ...]) -> Scores:
    expected = expectation_of(questions)
    categories = {question.id: question.category for question in questions}
    graded = [result for result in run.results if result.error is None]
    scored = [
        (result, expected[(result.id, result.actor)])
        for result in graded
        if (result.id, result.actor) in expected and result.scored
    ]
    retrieval = [(result, exp) for result, exp in scored if exp.gold_document_ids]
    abstaining = [
        result
        for result, exp in scored
        if exp.expect_abstain or categories.get(result.id) == "ambiguous"
    ]
    judged = [
        (result, exp) for result, exp in scored if result not in abstaining and exp.gold_answer
    ]
    durations = [result.seconds for result in graded if result.seconds]
    return Scores(
        questions=len(run.results),
        answered=len([result for result in graded if result.status == "COMPLETED"]),
        recall={
            k: _mean(
                [
                    metrics.recall_at(result.retrieved, exp.gold_document_ids, k)
                    for result, exp in retrieval
                ]
            )
            for k in recall_at
        },
        ndcg5=_mean(
            [
                metrics.ndcg_at(result.retrieved, exp.gold_document_ids, 5)
                for result, exp in retrieval
            ]
        ),
        citation_recall=_mean(
            [
                metrics.citation_recall(result.cited, exp.gold_document_ids)
                for result, exp in retrieval
            ]
        ),
        citation_precision=_mean(
            [
                metrics.citation_precision(result.cited, exp.gold_document_ids)
                for result, exp in retrieval
            ]
        ),
        correctness=_mean([1.0 if result.correct else 0.0 for result, _ in judged]),
        judge_disagreement=_mean(
            [
                0.0 if result.judge_agreed == result.judge_trials else 1.0
                for result, _ in judged
                if result.judge_trials
            ]
        ),
        partial_correctness=_mean(
            [1.0 if result.correct else 0.0 for result, exp in judged if exp.partial]
        ),
        abstention=_mean([1.0 if result.abstained else 0.0 for result in abstaining]),
        false_refusal=_mean(
            [
                1.0 if result.abstained else 0.0
                for result, _ in judged
                if result.abstained is not None
            ]
        ),
        asserted_uncited=sum(1 for result in graded if result.asserted_uncited),
        sensitive_answered=sum(
            1
            for result, _ in scored
            if categories.get(result.id) == "sensitive" and result.abstained is False
        ),
        leaks=sum(_leaks(result) for result in run.results),
        outside_corpus=sum(len(result.outside_corpus) for result in run.results),
        median_seconds=metrics.percentile(durations, 0.5),
        p90_seconds=metrics.percentile(durations, 0.9),
        errors=len([result for result in run.results if result.error]),
        by_actor=_by_actor(run.results),
        by_category=_by_category(graded),
    )


def _leaks(result: QuestionResult) -> int:
    return len(result.leaked) + len(result.leaked_facts)


def _by_actor(results: list[QuestionResult]) -> dict[str, ActorScores]:
    rows: dict[str, ActorScores] = {}
    for label in sorted({result.actor for result in results}):
        mine = [result for result in results if result.actor == label]
        verdicts = [result.correct for result in mine if result.correct is not None]
        rows[label] = ActorScores(
            asked=len(mine),
            correct=_mean([1.0 if verdict else 0.0 for verdict in verdicts]),
            leaks=sum(_leaks(result) for result in mine),
            judged=len(verdicts),
        )
    return rows


def _by_category(results: list[QuestionResult]) -> dict[str, CategoryScores]:
    rows: dict[str, CategoryScores] = {}
    for category in sorted({result.category for result in results}):
        mine = [result for result in results if result.category == category]
        rows[category] = CategoryScores(
            asked=len(mine),
            abstention=_mean(
                [
                    1.0 if result.abstained else 0.0
                    for result in mine
                    if result.abstained is not None
                ]
            ),
            asserted_uncited=sum(1 for result in mine if result.asserted_uncited),
        )
    return rows


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def markdown(run: Run, scores: Scores, baseline: Scores | None) -> str:
    """The report a person reads: every metric beside its baseline, with what regressed named."""

    rows = [
        ("Lượt hỏi (câu × actor)", scores.questions, baseline.questions if baseline else None),
        ("Trả lời xong", scores.answered, baseline.answered if baseline else None),
        *[
            (
                f"recall@{k}",
                round(value, 3),
                round(baseline.recall.get(k, 0.0), 3) if baseline else None,
            )
            for k, value in sorted(scores.recall.items())
        ],
        ("nDCG@5", round(scores.ndcg5, 3), round(baseline.ndcg5, 3) if baseline else None),
        (
            "Trích dẫn đúng (recall)",
            round(scores.citation_recall, 3),
            round(baseline.citation_recall, 3) if baseline else None,
        ),
        (
            "Trích dẫn sạch (precision)",
            round(scores.citation_precision, 3),
            round(baseline.citation_precision, 3) if baseline else None,
        ),
        (
            "Trả lời đúng",
            round(scores.correctness, 3),
            round(baseline.correctness, 3) if baseline else None,
        ),
        (
            "Trả lời đúng một phần (xuyên phòng ban)",
            round(scores.partial_correctness, 3),
            round(baseline.partial_correctness, 3) if baseline else None,
        ),
        (
            "Từ chối đúng",
            round(scores.abstention, 3),
            round(baseline.abstention, 3) if baseline else None,
        ),
        (
            "Từ chối nhầm câu trả lời được",
            round(scores.false_refusal, 3),
            round(baseline.false_refusal, 3) if baseline else None,
        ),
        (
            "Khẳng định không trích dẫn",
            scores.asserted_uncited,
            baseline.asserted_uncited if baseline else None,
        ),
        (
            "Câu nhạy cảm được trả lời",
            scores.sensitive_answered,
            baseline.sensitive_answered if baseline else None,
        ),
        (
            "Giám khảo không thống nhất (đo lường, không phải hệ thống)",
            round(scores.judge_disagreement, 3),
            round(baseline.judge_disagreement, 3) if baseline else None,
        ),
        ("Rò rỉ quyền", scores.leaks, baseline.leaks if baseline else None),
        (
            "Tài liệu ngoài corpus của actor (cảnh báo)",
            scores.outside_corpus,
            baseline.outside_corpus if baseline else None,
        ),
        (
            "Thời gian trung vị (giây)",
            scores.median_seconds,
            baseline.median_seconds if baseline else None,
        ),
        # Question sent to finished reply read from history; the first text is not observed.
        ("Thời gian p90 (giây)", scores.p90_seconds, baseline.p90_seconds if baseline else None),
        ("Lỗi khi chạy", scores.errors, baseline.errors if baseline else None),
    ]
    lines = [
        f"# Benchmark {run.label}",
        "",
        f"Chạy lúc {run.started_at}",
        "",
        f"Chế độ grounded: {'bật' if run.grounded else 'tắt'}",
        "",
        "| Chỉ số | Lần này | Baseline |",
        "| --- | --- | --- |",
    ]
    lines += [
        f"| {name} | {value} | {'—' if prior is None else prior} |" for name, value, prior in rows
    ]
    if scores.by_actor:
        lines += [
            "",
            "## Theo actor",
            "",
            "| Actor | Lượt hỏi | Đúng | Rò rỉ |",
            "| --- | --- | --- | --- |",
        ]
        for label, row in sorted(scores.by_actor.items()):
            correct = f"{row.correct:.3f}" if row.judged else "—"
            lines.append(f"| {label} | {row.asked} | {correct} | {row.leaks} |")
    if scores.by_category:
        lines += [
            "",
            "## Theo nhóm câu hỏi",
            "",
            "| Nhóm | Lượt hỏi | Từ chối | Khẳng định không trích dẫn |",
            "| --- | --- | --- | --- |",
        ]
        for category, group in sorted(scores.by_category.items()):
            lines.append(
                f"| {category} | {group.asked} | {group.abstention:.3f} "
                f"| {group.asserted_uncited} |"
            )
    failures = regressions(scores, baseline, grounded=run.grounded)
    lines += ["", "## Kết luận", ""]
    if baseline and baseline.questions != scores.questions:
        lines += [
            f"Lưu ý: baseline ghi {baseline.questions} lượt hỏi, lần này {scores.questions}; "
            "so sánh chỉ có nghĩa trên cùng bộ câu hỏi; nếu bộ câu hỏi đã đổi, ghi lại baseline.",
            "",
        ]
    lines += ["Đạt: không có chỉ số nào tụt so với baseline." if not failures else "Không đạt:"]
    lines += [f"- {failure}" for failure in failures]
    return "\n".join(lines) + "\n"


def regressions(scores: Scores, baseline: Scores | None, grounded: bool = False) -> list[str]:
    """
    Leakage fails on its own; every other metric fails only when it drops below the baseline. A
    grounded run also fails on any answer without a valid citation and on any answered sensitive
    question, because grounded mode promises both are zero.
    """
    failures: list[str] = []
    if scores.leaks:
        failures.append(
            f"rò rỉ quyền: {scores.leaks} tài liệu hoặc dữ kiện không được phép đã tới actor"
        )
    if scores.errors:
        # A run whose asks failed measured nothing; it must never read as a pass.
        failures.append(f"lỗi khi chạy: {scores.errors} lượt hỏi không hoàn thành")
    if grounded and scores.asserted_uncited:
        failures.append(
            f"khẳng định không trích dẫn: {scores.asserted_uncited} câu trả lời không nêu nguồn"
        )
    if grounded and scores.sensitive_answered:
        failures.append(f"câu nhạy cảm được trả lời: {scores.sensitive_answered}")
    if baseline is None:
        return failures
    checks: list[tuple[str, float, float]] = [
        ("nDCG@5", scores.ndcg5, baseline.ndcg5),
        ("trích dẫn đúng", scores.citation_recall, baseline.citation_recall),
        ("trả lời đúng", scores.correctness, baseline.correctness),
        ("trả lời đúng một phần", scores.partial_correctness, baseline.partial_correctness),
        ("từ chối đúng", scores.abstention, baseline.abstention),
    ]
    checks += [
        (f"recall@{k}", value, baseline.recall.get(k, 0.0)) for k, value in scores.recall.items()
    ]
    # A run is tens of questions, so a fraction of a percent is noise, not a regression.
    failures += [
        f"{name}: {value:.3f} < baseline {prior:.3f}"
        for name, value, prior in checks
        if value + 0.005 < prior
    ]
    return failures


def load_baseline(path: Path) -> Scores | None:
    """A missing file, or one that records no question, is a baseline not yet recorded."""
    if not path.exists():
        return None
    row = json.loads(path.read_text(encoding="utf-8"))
    if not int(row.get("questions", 0)):
        return None
    return Scores(
        questions=int(row["questions"]),
        answered=int(row["answered"]),
        recall={int(k): float(v) for k, v in row["recall"].items()},
        ndcg5=float(row["ndcg5"]),
        citation_recall=float(row["citationRecall"]),
        citation_precision=float(row["citationPrecision"]),
        correctness=float(row["correctness"]),
        partial_correctness=float(row.get("partialCorrectness", 0.0)),
        judge_disagreement=float(row.get("judgeDisagreement", 0.0)),
        abstention=float(row["abstention"]),
        leaks=int(row["leaks"]),
        outside_corpus=int(row.get("outsideCorpus", 0)),
        median_seconds=float(row["medianSeconds"]),
        p90_seconds=float(row["p90Seconds"]),
        errors=int(row["errors"]),
        asserted_uncited=int(row.get("assertedUncited", 0)),
        false_refusal=float(row.get("falseRefusal", 0.0)),
        sensitive_answered=int(row.get("sensitiveAnswered", 0)),
    )


def save_baseline(path: Path, scores: Scores) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(scores.as_json(), indent=2) + "\n", encoding="utf-8")


def failures(results: list[QuestionResult], grounded: bool = False) -> list[QuestionResult]:
    """
    Results worth reading by hand: an error, a leak, a document outside the corpus, a miss, and in
    a grounded run an answer without a valid citation, which fails that run even when correct.
    """
    return [
        result
        for result in results
        if result.error
        or result.leaked
        or result.leaked_facts
        or result.outside_corpus
        or result.correct is False
        or (grounded and result.asserted_uncited)
    ]
