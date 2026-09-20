"""Scores a run, compares it with a baseline and decides whether the candidate is acceptable."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from . import metrics
from .questions import Question
from .run import QuestionResult, Run


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

    def as_json(self) -> dict[str, object]:
        return {
            "questions": self.questions,
            "answered": self.answered,
            "recall": {str(k): round(v, 4) for k, v in self.recall.items()},
            "ndcg5": round(self.ndcg5, 4),
            "citationRecall": round(self.citation_recall, 4),
            "citationPrecision": round(self.citation_precision, 4),
            "correctness": round(self.correctness, 4),
            "abstention": round(self.abstention, 4),
            "leaks": self.leaks,
            "medianSeconds": round(self.median_seconds, 2),
            "p90Seconds": round(self.p90_seconds, 2),
            "errors": self.errors,
        }


def score(run: Run, questions: list[Question], recall_at: tuple[int, ...]) -> Scores:
    by_id = {question.id: question for question in questions}
    graded = [result for result in run.results if result.error is None]
    retrieval = [
        (result, by_id[result.id]) for result in graded if by_id[result.id].gold_document_ids
    ]
    abstaining = [
        result
        for result in graded
        if by_id[result.id].expect_abstain or by_id[result.id].category == "ambiguous"
    ]
    judged = [
        result for result in graded if result not in abstaining and by_id[result.id].gold_answer
    ]
    durations = [result.seconds for result in graded if result.seconds]
    return Scores(
        questions=len(run.results),
        answered=len([result for result in graded if result.status == "COMPLETED"]),
        recall={
            k: _mean(
                [
                    metrics.recall_at(result.retrieved, question.gold_document_ids, k)
                    for result, question in retrieval
                ]
            )
            for k in recall_at
        },
        ndcg5=_mean(
            [
                metrics.ndcg_at(result.retrieved, question.gold_document_ids, 5)
                for result, question in retrieval
            ]
        ),
        citation_recall=_mean(
            [
                metrics.citation_recall(result.cited, question.gold_document_ids)
                for result, question in retrieval
            ]
        ),
        citation_precision=_mean(
            [
                metrics.citation_precision(result.cited, question.gold_document_ids)
                for result, question in retrieval
            ]
        ),
        correctness=_mean([1.0 if result.correct else 0.0 for result in judged]),
        abstention=_mean([1.0 if result.abstained else 0.0 for result in abstaining]),
        leaks=sum(len(result.leaked) for result in graded),
        median_seconds=metrics.percentile(durations, 0.5),
        p90_seconds=metrics.percentile(durations, 0.9),
        errors=len([result for result in run.results if result.error]),
    )


def _mean(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def markdown(run: Run, scores: Scores, baseline: Scores | None) -> str:
    """The report a person reads: every metric beside its baseline, with what regressed named."""
    rows = [
        ("Câu hỏi", scores.questions, baseline.questions if baseline else None),
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
            "Từ chối đúng",
            round(scores.abstention, 3),
            round(baseline.abstention, 3) if baseline else None,
        ),
        ("Rò rỉ quyền", scores.leaks, baseline.leaks if baseline else None),
        (
            "Thời gian trung vị (giây)",
            scores.median_seconds,
            baseline.median_seconds if baseline else None,
        ),
        ("Thời gian p90 (giây)", scores.p90_seconds, baseline.p90_seconds if baseline else None),
        ("Lỗi khi chạy", scores.errors, baseline.errors if baseline else None),
    ]
    lines = [
        f"# Benchmark {run.label}",
        "",
        f"Chạy lúc {run.started_at}",
        "",
        "| Chỉ số | Lần này | Baseline |",
        "| --- | --- | --- |",
    ]
    lines += [
        f"| {name} | {value} | {'—' if base is None else base} |" for name, value, base in rows
    ]
    failures = regressions(scores, baseline)
    lines += ["", "## Kết luận", ""]
    lines += ["Đạt: không có chỉ số nào tụt so với baseline." if not failures else "Không đạt:"]
    lines += [f"- {failure}" for failure in failures]
    return "\n".join(lines) + "\n"


def regressions(scores: Scores, baseline: Scores | None) -> list[str]:
    """Leakage fails on its own; every other metric fails only when it drops below the baseline."""
    failures: list[str] = []
    if scores.leaks:
        failures.append(f"rò rỉ quyền: {scores.leaks} trích dẫn không được phép")
    if baseline is None:
        return failures
    checks: list[tuple[str, float, float]] = [
        ("nDCG@5", scores.ndcg5, baseline.ndcg5),
        ("trích dẫn đúng", scores.citation_recall, baseline.citation_recall),
        ("trả lời đúng", scores.correctness, baseline.correctness),
        ("từ chối đúng", scores.abstention, baseline.abstention),
    ]
    checks += [
        (f"recall@{k}", value, baseline.recall.get(k, 0.0)) for k, value in scores.recall.items()
    ]
    # A run is tens of questions, so a fraction of a percent is noise, not a regression.
    failures += [
        f"{name}: {value:.3f} < baseline {base:.3f}"
        for name, value, base in checks
        if value + 0.005 < base
    ]
    return failures


def load_baseline(path: Path) -> Scores | None:
    if not path.exists():
        return None
    row = json.loads(path.read_text(encoding="utf-8"))
    return Scores(
        questions=int(row["questions"]),
        answered=int(row["answered"]),
        recall={int(k): float(v) for k, v in row["recall"].items()},
        ndcg5=float(row["ndcg5"]),
        citation_recall=float(row["citationRecall"]),
        citation_precision=float(row["citationPrecision"]),
        correctness=float(row["correctness"]),
        abstention=float(row["abstention"]),
        leaks=int(row["leaks"]),
        median_seconds=float(row["medianSeconds"]),
        p90_seconds=float(row["p90Seconds"]),
        errors=int(row["errors"]),
    )


def save_baseline(path: Path, scores: Scores) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(scores.as_json(), indent=2) + "\n", encoding="utf-8")


def failures(results: list[QuestionResult]) -> list[QuestionResult]:
    """Questions worth reading by hand: an error, a leak, or a wrong answer."""
    return [
        result for result in results if result.error or result.leaked or result.correct is False
    ]
