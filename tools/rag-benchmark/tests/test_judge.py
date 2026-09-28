"""
The judge's verdict. Three trials exist because the judging model is not deterministic in practice:
a borderline answer that scores 1-of-3 or 2-of-3 must not be recorded as a single confident verdict.
"""

from __future__ import annotations

from dataclasses import replace
from pathlib import Path
from typing import Any

import httpx
import pytest

from rag_benchmark.config import Config
from rag_benchmark.judge import Judge


def config(trials: int) -> Config:
    return Config(
        base_url="https://memoryos.test",
        issuer="https://auth.test/realms/memoryos",
        actors={},
        judge_model="judge",
        judge_base_url="https://judge.test",
        judge_api_key="key",
        judge_trials=trials,
        data_dir=Path("datasets"),
        out_dir=Path("runs"),
        token_store=Path("tokens.json"),
    )


class Response:
    def __init__(self, verdict: str, status_code: int = 200) -> None:
        self.status_code = status_code
        self._verdict = verdict

    def json(self) -> dict[str, Any]:
        return {"choices": [{"message": {"content": self._verdict}}]}


def replies(monkeypatch: pytest.MonkeyPatch, *responses: Response) -> list[dict[str, Any]]:
    sent: list[dict[str, Any]] = []
    queue = list(responses)

    def post(_url: str, **kwargs: Any) -> Response:  # noqa: ANN401
        sent.append(kwargs["json"])
        return queue.pop(0)

    monkeypatch.setattr(httpx, "post", post)
    return sent


def test_the_majority_of_three_trials_is_the_score(monkeypatch: pytest.MonkeyPatch) -> None:
    sent = replies(
        monkeypatch,
        Response('{"correct": true, "reason": "nêu đúng số liệu"}'),
        Response('{"correct": false, "reason": "thiếu tháng"}'),
        Response('{"correct": true, "reason": "nêu đúng số liệu"}'),
    )

    verdict = Judge(config(3)).score("hỏi", "vàng", "đáp")

    assert len(sent) == 3
    assert verdict.correct
    # A split is recorded, so a 2/3 is not reread later as a unanimous verdict.
    assert verdict.reason.startswith("2/3 chấm đúng")
    assert (verdict.agreed, verdict.trials) == (2, 3)


def test_a_unanimous_verdict_keeps_its_own_reason(monkeypatch: pytest.MonkeyPatch) -> None:
    replies(
        monkeypatch,
        Response('{"correct": false, "reason": "sai số liệu"}'),
        Response('{"correct": false, "reason": "sai số liệu"}'),
        Response('{"correct": false, "reason": "sai số liệu"}'),
    )

    verdict = Judge(config(3)).score("hỏi", "vàng", "đáp")

    assert not verdict.correct
    assert verdict.reason == "sai số liệu"
    # Unanimous, so the report must not count this answer as a soft measurement.
    assert (verdict.agreed, verdict.trials) == (3, 3)


def test_a_broken_call_is_reported_rather_than_outvoted(monkeypatch: pytest.MonkeyPatch) -> None:
    replies(
        monkeypatch,
        Response('{"correct": true, "reason": "đúng"}'),
        Response("", status_code=502),
        Response('{"correct": true, "reason": "đúng"}'),
    )

    verdict = Judge(config(3)).score("hỏi", "vàng", "đáp")

    assert not verdict.correct
    assert verdict.reason == "judge call failed (502)"


def test_the_rubric_scores_coverage_rather_than_resemblance(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sent = replies(monkeypatch, Response('{"correct": true, "reason": "đủ ý"}'))

    Judge(config(1)).score("hỏi", "vàng", "đáp")

    system = sent[0]["messages"][0]["content"]
    # The rule that marked 19 of 24 correct answers wrong on staging must not come back.
    assert "KHÔNG bị trừ điểm" in system
    assert "thêm thông tin không có trong đáp án chuẩn" not in system
    assert "nêu đủ mọi ý và số liệu của đáp án chuẩn" in system


def test_the_partial_prompt_does_not_require_announcing_the_missing_part(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sent = replies(monkeypatch, Response('{"correct": true, "reason": "đúng phần được đọc"}'))

    Judge(config(1)).score("hỏi", "vàng", "đáp", partial=True)

    system = sent[0]["messages"][0]["content"]
    assert "không khẳng định phần còn lại" in system
    assert "Không bắt buộc phải nói ra" in system


def test_the_temperature_is_sent_only_when_configured(monkeypatch: pytest.MonkeyPatch) -> None:
    sent = replies(
        monkeypatch,
        Response('{"correct": true, "reason": "đúng"}'),
        Response('{"correct": true, "reason": "đúng"}'),
    )

    Judge(config(1)).score("hỏi", "vàng", "đáp")
    Judge(replace(config(1), judge_temperature=None)).score("hỏi", "vàng", "đáp")

    assert sent[0]["temperature"] == 0.0
    # A reasoning judge refuses any temperature but its default, so none is sent.
    assert "temperature" not in sent[1]
