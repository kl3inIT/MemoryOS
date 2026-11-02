import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "promptfoo"))

import assertions  # noqa: E402

LINES = [
    {"n": 1, "speaker": "MIC/1", "text": "Để em gửi path spec của bucket"},
    {"n": 2, "speaker": "MIC/2", "text": "Chốt dùng Google Drive của dự án nhé"},
    {"n": 3, "speaker": "MIC/2", "text": "Chị Lan làm báo cáo tuần"},
]
SPEAKERS = [{"track": "MIC", "label": "1", "name": None}, {"track": "MIC", "label": "2", "name": "Lan"}]


def run(tmp_path, monkeypatch, written, key, matches):
    path = tmp_path / "key.json"
    path.write_text(json.dumps(key), encoding="utf-8")
    monkeypatch.setattr(assertions, "_judge", lambda prompt: {"matches": matches})
    output = {"lines": LINES, "speakers": SPEAKERS, **written}
    return assertions.score(output, {"vars": {"key": str(path)}})


def test_counts_found_work_owners_and_duplicates(tmp_path, monkeypatch):
    key = {
        "decisions": [{"text": "Dùng Google Drive", "line": 2}],
        "actions": [
            {"text": "Gửi path spec", "line": 1, "owner": {"track": "MIC", "label": "1"}},
            {"text": "Làm báo cáo tuần", "line": 3, "owner": {"name": "lan"}},
            {"text": "Hỏi Hà về Drive", "line": 2, "owner": None},
        ],
    }
    written = {
        "decisions": [{"text": "Dùng Drive", "line": 2, "quote": "dùng Google Drive"}],
        "actions": [
            {"text": "Gửi spec", "line": 1, "owner": {"track": "MIC", "label": "1"}, "quote": "không có"},
            {"text": "Gửi spec bucket", "line": 1, "owner": None},
            # Named voice MIC/2 is "Lan", so it owns what the key gives to Lan by name.
            {"text": "Báo cáo tuần", "line": 3, "owner": {"track": "MIC", "label": "2"}},
            {"text": "Việc không có trong biên bản", "line": 3, "owner": {"name": "Tùng"}},
        ],
    }
    matches = {"W1": "K1", "W2": "K2", "W3": "K2", "W4": "K3", "W5": None}
    named = run(tmp_path, monkeypatch, written, key, matches)["namedScores"]

    assert named["decision_recall"] == 1.0
    assert named["action_recall"] == round(2 / 3, 4)
    assert named["action_precision"] == 0.75
    assert named["owner_accuracy"] == 1.0
    assert named["duplicates"] == 0.2
    assert named["quote_in_line"] == 0.5


def test_a_wrong_owner_and_a_missing_one_are_told_apart(tmp_path, monkeypatch):
    key = {"decisions": [], "actions": [
        {"text": "Gửi path spec", "line": 1, "owner": {"track": "MIC", "label": "1"}},
        {"text": "Làm báo cáo tuần", "line": 3, "owner": {"track": "MIC", "label": "2"}},
    ]}
    written = {"decisions": [], "actions": [
        {"text": "Gửi spec", "line": 1, "owner": {"track": "MIC", "label": "2"}},
        {"text": "Báo cáo", "line": 3, "owner": None},
    ]}
    result = run(tmp_path, monkeypatch, written, key, {"W1": "K1", "W2": "K2"})
    counts = json.loads(result["reason"])["counts"]

    assert result["namedScores"]["owner_accuracy"] == 0.0
    assert result["namedScores"]["owner_wrong"] == 0.5
    assert counts["owner_missing"] == 1


def test_a_match_across_kinds_or_out_of_range_counts_as_none(tmp_path, monkeypatch):
    key = {"decisions": [{"text": "Dùng Drive", "line": 2}], "actions": []}
    written = {"decisions": [], "actions": [{"text": "Dùng Drive", "line": 2, "owner": None}]}
    named = run(tmp_path, monkeypatch, written, key, {"W1": "K1"})["namedScores"]

    assert named["decision_recall"] == 0.0
    assert named["action_precision"] == 0.0
