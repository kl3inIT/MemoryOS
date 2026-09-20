"""One benchmark run: retrieval per question, one Chat turn per question, judged and scored."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from . import metrics
from .client import ActorClient, BenchmarkError
from .config import Config
from .judge import Judge
from .questions import Question


@dataclass
class QuestionResult:
    id: str
    category: str
    actor: str
    retrieved: list[str] = field(default_factory=list)
    cited: list[str] = field(default_factory=list)
    answer: str = ""
    status: str = ""
    seconds: float = 0.0
    steps: int = 0
    correct: bool | None = None
    judge_reason: str = ""
    abstained: bool | None = None
    leaked: list[str] = field(default_factory=list)
    error: str | None = None


@dataclass
class Run:
    label: str
    started_at: str
    results: list[QuestionResult]

    def write(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "label": self.label,
                    "startedAt": self.started_at,
                    "results": [asdict(result) for result in self.results],
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        return path


def execute(
    config: Config, questions: list[Question], label: str, retrieval_only: bool = False
) -> Run:
    clients: dict[str, ActorClient] = {}
    judge = Judge(config)
    results: list[QuestionResult] = []
    try:
        for question in questions:
            result = QuestionResult(
                id=question.id, category=question.category, actor=question.actor
            )
            try:
                client = _client(clients, config, question.actor)
                result.retrieved = [
                    str(row["documentId"])
                    for row in client.search(question.question, max(config.recall_at))
                ]
                if not retrieval_only:
                    _answer(client, judge, question, result)
                    if question.denied_actor:
                        _check_denied(clients, config, question, result)
            except BenchmarkError as failure:
                result.error = str(failure)
            results.append(result)
    finally:
        for client in clients.values():
            client.close()
    return Run(
        label=label, started_at=datetime.now(UTC).isoformat(timespec="seconds"), results=results
    )


def _client(clients: dict[str, ActorClient], config: Config, label: str) -> ActorClient:
    if label not in clients:
        actor = config.actors.get(label)
        if actor is None:
            raise BenchmarkError(
                f"question names actor {label!r}, which the run does not configure"
            )
        clients[label] = ActorClient(config, actor)
    return clients[label]


def _answer(client: ActorClient, judge: Judge, question: Question, result: QuestionResult) -> None:
    reply = client.ask(question.question)
    result.cited = reply.document_ids
    result.answer = reply.content
    result.status = reply.status
    result.seconds = round(reply.seconds, 2)
    result.steps = reply.steps
    result.abstained = metrics.abstained(reply.content, reply.document_ids)
    if question.expect_abstain or question.category == "ambiguous":
        # Nothing to judge: the contract is that the reply declines or asks, and cites nothing.
        result.correct = result.abstained
        result.judge_reason = "abstention expected"
        return
    verdict = judge.score(question.question, question.gold_answer, reply.content)
    result.correct = verdict.correct
    result.judge_reason = verdict.reason


def _check_denied(
    clients: dict[str, ActorClient], config: Config, question: Question, result: QuestionResult
) -> None:
    """
    The unauthorized side of a group question: the same question must reach none of the gold
    documents.
    """
    assert question.denied_actor  # noqa: S101 - questions.py enforces it for group questions
    denied = _client(clients, config, question.denied_actor)
    reply = denied.ask(question.question)
    result.leaked = sorted(set(reply.document_ids) & set(question.gold_document_ids))
