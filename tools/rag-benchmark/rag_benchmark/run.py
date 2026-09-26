"""
One benchmark run. Every question is asked as each actor it names: retrieval through the search
page, one Chat turn, a judge verdict where an answer is expected, and an authority check on every
surface the actor saw.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from . import leaks, metrics
from .client import ActorClient, BenchmarkError
from .config import Config
from .judge import Judge
from .questions import Expectation, Question


@dataclass
class QuestionResult:
    id: str
    category: str
    actor: str
    # False for the denied side of a group question: checked for leaks, never scored.
    scored: bool = True
    partial: bool = False
    retrieved: list[str] = field(default_factory=list)
    cited: list[str] = field(default_factory=list)
    # The citation numbers of the reply's sources, which an inline `[n]` must name to count.
    citation_ids: list[int] = field(default_factory=list)
    # Documents the Chat timeline read while answering.
    read: list[str] = field(default_factory=list)
    # Queries, filters and documents of each tool step, so a failure reads without a rerun.
    timeline: list[dict[str, object]] = field(default_factory=list)
    answer: str = ""
    status: str = ""
    seconds: float = 0.0
    steps: int = 0
    correct: bool | None = None
    judge_reason: str = ""
    # Agreeing trials out of trials run; below one means the judge contradicted itself.
    # Which model answered, so two runs can be compared later without guessing.
    model: str | None = None
    fallback: str | None = None
    judge_agreed: int = 0
    judge_trials: int = 0
    abstained: bool | None = None
    # The server's reason for declining, when it gave one.
    refusal_reason: str | None = None
    # An answer with no inline citation naming one of its sources: zero with grounded mode on.
    asserted_uncited: bool | None = None
    # Forbidden documents that reached the actor on any surface, and forbidden facts in the answer.
    leaked: list[str] = field(default_factory=list)
    leaked_facts: list[str] = field(default_factory=list)
    # Documents outside the actor's frozen corpus: a warning, since probes may miss a document.
    outside_corpus: list[str] = field(default_factory=list)
    error: str | None = None


@dataclass
class Run:
    label: str
    started_at: str
    results: list[QuestionResult]
    # Run against a Tenant or agent with grounded answers on, and compared with its own baseline.
    grounded: bool = False

    def write(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "label": self.label,
                    "startedAt": self.started_at,
                    "grounded": self.grounded,
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
    config: Config,
    questions: list[Question],
    label: str,
    retrieval_only: bool = False,
    readable: dict[str, set[str]] | None = None,
    grounded: bool = False,
) -> Run:
    """`readable` maps an actor label to the documents of its frozen corpus, when one was frozen."""
    clients: dict[str, ActorClient] = {}
    judge = Judge(config)
    results: list[QuestionResult] = []
    started_at = datetime.now(UTC).isoformat(timespec="seconds")
    try:
        for question in questions:
            for expectation in question.expectations():
                results.append(
                    _ask(
                        clients,
                        config,
                        judge,
                        question,
                        expectation,
                        retrieval_only,
                        (readable or {}).get(expectation.actor),
                        grounded,
                    )
                )
    finally:
        for client in clients.values():
            client.close()
    return Run(label=label, started_at=started_at, results=results, grounded=grounded)


def _ask(
    clients: dict[str, ActorClient],
    config: Config,
    judge: Judge,
    question: Question,
    expectation: Expectation,
    retrieval_only: bool,
    corpus: set[str] | None,
    grounded: bool = False,
) -> QuestionResult:
    result = QuestionResult(
        id=question.id,
        category=question.category,
        actor=expectation.actor,
        scored=expectation.scored,
        partial=expectation.partial,
    )
    try:
        client = _client(clients, config, expectation.actor)
        result.retrieved = [
            str(row["documentId"])
            for row in client.search(question.question, max(config.recall_at))
        ]
        if not retrieval_only:
            _answer(client, question, result)
    except BenchmarkError as failure:
        result.error = str(failure)
    seen = set(result.retrieved) | set(result.cited) | set(result.read)
    result.leaked = leaks.documents(seen, expectation.forbidden_document_ids)
    result.leaked_facts = leaks.facts(result.answer, expectation.forbidden_facts)
    if corpus is not None:
        result.outside_corpus = sorted(seen - corpus)
    if result.error is None and not retrieval_only:
        # Scored after the leak check, because a question that expects a refusal is scored on what
        # the reply reached, not on its wording alone.
        _score(judge, question, expectation, result, grounded)
    return result


def _client(clients: dict[str, ActorClient], config: Config, label: str) -> ActorClient:
    if label not in clients:
        actor = config.actors.get(label)
        if actor is None:
            raise BenchmarkError(
                f"question names actor {label!r}, which the run does not configure"
            )
        clients[label] = ActorClient(config, actor)
    return clients[label]


def _answer(client: ActorClient, question: Question, result: QuestionResult) -> None:
    reply = client.ask(question.question)
    result.cited = reply.document_ids
    result.citation_ids = reply.citation_ids
    result.refusal_reason = reply.refusal_reason
    result.read = reply.read_document_ids
    result.timeline = reply.timeline
    result.answer = reply.content
    result.status = reply.status
    result.seconds = round(reply.seconds, 2)
    result.steps = reply.steps
    result.model = reply.model
    result.fallback = reply.fallback


def _score(
    judge: Judge,
    question: Question,
    expectation: Expectation,
    result: QuestionResult,
    grounded: bool = False,
) -> None:
    result.abstained = metrics.abstained(
        result.answer,
        result.cited,
        expectation.forbidden_document_ids,
        result.refusal_reason,
    )
    # Measured on every side of a question, the denied side of a group question included. With
    # grounded answers on, only the recorded refusal declines: a reply that merely sounds like one
    # but carries no refusal reason escaped the citation gate and must count.
    declined = bool(result.refusal_reason) if grounded else result.abstained
    result.asserted_uncited = metrics.asserted_without_citation(
        result.answer, result.citation_ids, declined, result.status
    )
    if not expectation.scored:
        return
    if expectation.expect_abstain or question.category == "ambiguous":
        # The contract is that the reply declines and delivers none of the evidence it may not read.
        # Citing a document the actor is allowed to see while declining still honours it; naming a
        # forbidden document or one of its facts, whether from the corpus or from general
        # knowledge, does not.
        result.correct = result.abstained and not result.leaked and not result.leaked_facts
        reasons = []
        if result.leaked:
            reasons.append(f"chạm tài liệu cấm: {', '.join(result.leaked)}")
        if result.leaked_facts:
            reasons.append(f"nêu dữ kiện cấm: {', '.join(result.leaked_facts)}")
        if not result.abstained:
            reasons.append("không từ chối")
        declined = "đã từ chối, không lộ gì"
        if result.refusal_reason:
            declined = f"đã từ chối ({result.refusal_reason}), không lộ gì"
        result.judge_reason = "; ".join(reasons) if reasons else declined
        return
    verdict = judge.score(
        question.question, expectation.gold_answer, result.answer, partial=expectation.partial
    )
    result.correct = verdict.correct
    result.judge_reason = verdict.reason
    result.judge_agreed = verdict.agreed
    result.judge_trials = verdict.trials
