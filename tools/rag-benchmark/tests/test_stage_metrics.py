import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "promptfoo"))

import assertions  # noqa: E402
import build_config  # noqa: E402
import summary  # noqa: E402


def _context(gold: list[str]) -> dict:
    return {"vars": {"gold": json.dumps(gold)}}


def test_context_recall_and_precision_count_gold_documents_among_those_read() -> None:
    output = {"readDocuments": ["a", "x", "y"]}
    recall = assertions.context_recall(output, _context(["a", "b"]))
    precision = assertions.context_precision(output, _context(["a", "b"]))
    assert recall["pass"] and recall["score"] == 0.5
    assert "missed b" in recall["reason"]
    assert precision["pass"] and precision["score"] == 1 / 3


def test_context_precision_is_zero_when_nothing_was_read() -> None:
    assert assertions.context_precision({"readDocuments": []}, _context(["a"]))["score"] == 0


def test_gold_documents_leave_out_what_the_role_may_not_read() -> None:
    question = {"evidence": [{"document": "a"}, {"document": "b"}, {"document": "a"}]}
    assert build_config.gold_documents(question, {"forbidden_documents": ["b"]}) == ["a"]


def test_stage_checks_measure_reading_only_where_the_role_can_read_gold() -> None:
    question = {"evidence": [{"document": "a"}], "gold_answer": "x"}
    answer = build_config.stage_assertions(
        question, {"expect": "answer", "forbidden_documents": []}
    )
    blind = build_config.stage_assertions(
        question, {"expect": "partial", "forbidden_documents": ["a"]}
    )
    metrics = [c["metric"] for c in answer]
    assert metrics == [
        "context_recall",
        "context_precision",
        "completeness",
        "correctness_sol",
        "completeness_sol",
    ]
    assert blind == []


def test_kappa_is_one_on_full_agreement_and_zero_at_chance() -> None:
    assert summary.kappa([(True, True), (False, False)]) == (1.0, 1.0)
    observed, k = summary.kappa([(True, True), (True, False), (False, True), (False, False)])
    assert observed == 0.5 and k == 0


def test_bootstrap_resamples_questions_and_brackets_the_mean() -> None:
    clusters = {"q1": [1.0, 1.0], "q2": [0.0], "q3": [1.0]}
    point, low, high = summary.bootstrap(clusters, summary.mean)
    assert point == 0.75
    assert 0 <= low <= point <= high <= 1


def test_percentile_takes_the_nearest_rank() -> None:
    assert summary.percentile(0.5)([3.0, 1.0, 2.0]) == 2.0
    assert summary.percentile(0.95)([float(n) for n in range(1, 21)]) == 20.0


def _ranked(*documents: str, sections: dict | None = None) -> dict:
    return {"ranked": [{"document": d, "sections": (sections or {}).get(d, [])} for d in documents]}


def _vars(**values: object) -> dict:
    return {"vars": {k: json.dumps(v, ensure_ascii=False) for k, v in values.items()}}


def test_ranking_metrics_match_hand_computed_values() -> None:
    # Gold a at rank 2 and b at rank 12 of the list.
    output = _ranked("x", "a", *[f"n{i}" for i in range(9)], "b")
    context = _vars(gold=["a", "b"])
    assert assertions.recall_at_10(output, context)["score"] == 0.5
    assert assertions.recall_at_50(output, context)["score"] == 1.0
    assert assertions.mrr_at_10(output, context)["score"] == 0.5
    # DCG = 1/log2(3); ideal = 1 + 1/log2(3).
    expected = (1 / math.log2(3)) / (1 + 1 / math.log2(3))
    assert math.isclose(assertions.ndcg_at_10(output, context)["score"], expected)


def test_a_gold_document_outside_the_list_scores_zero() -> None:
    output, context = _ranked("x", "y"), _vars(gold=["a"])
    assert assertions.ndcg_at_10(output, context)["score"] == 0
    assert assertions.mrr_at_10(output, context)["score"] == 0


def test_a_quote_is_found_despite_split_figures_but_not_in_other_text() -> None:
    quote = "Lợi nhuận sau thuế năm 2025 đạt 46.621.079.163 đồng theo báo cáo"
    spaced = (
        "Theo đó, lợi nhuận sau thuế năm 2025 đạt 46.621.079. 163 đồng theo báo cáo đã kiểm toán"
    )
    assert assertions.quote_found(quote, spaced)
    assert not assertions.quote_found(quote, "Doanh thu thuần năm 2025 tăng 12% so với năm trước")


def test_a_quote_of_fragments_is_found_across_split_words() -> None:
    quote = "Số: 04/TTr-HĐQT-SVC | Tp. Hồ Chí Minh, ngày 14 tháng 05 năm 2026"
    text = "Tp. Hồ Chí M inh,  ngày 14 tháng 05 năm 20 26 Số: 04/TTrHĐQT -SVC"
    assert assertions.quote_found(quote, text)
    assert not assertions.quote_found(quote + " | Tỷ lệ chi trả: 05%", text)


def test_a_table_quote_matches_cells_the_index_holds_in_another_order() -> None:
    quote = "1. Vốn góp của chủ sở hữu\n411\n932.782.890.000"
    text = (
        "| Chỉ tiêu | Mã số | Số cuối kỳ |\n| 1. Vốn góp của chủ sở hữu | 411 | 932.782.890.000 |"
    )
    assert assertions.quote_found(quote, text)


def test_extraction_and_passage_recall_read_the_quote_in_its_own_document() -> None:
    quote = {"document": "a", "quote": "vốn điều lệ của công ty là 100 tỷ đồng"}
    text = "Điều 5. Vốn điều lệ của công ty là 100 tỷ đồng."
    output = _ranked("b", "a", sections={"a": [text], "b": ["không liên quan"]})
    output["goldText"] = {"a": text}
    context = _vars(quotes=[quote], gold=["a"])
    assert assertions.extraction_coverage(output, context)["score"] == 1
    assert assertions.passage_recall_at_10(output, context)["score"] == 1
    output["goldText"] = {"a": "trang scan không đọc được"}
    assert assertions.extraction_coverage(output, context)["score"] == 0


def test_tempting_first_only_when_the_tempting_document_outranks_every_gold_one() -> None:
    assert (
        assertions.tempting_first(_ranked("t", "a"), _vars(gold=["a"], tempting=["t"]))["score"]
        == 1
    )
    assert (
        assertions.tempting_first(_ranked("a", "t"), _vars(gold=["a"], tempting=["t"]))["score"]
        == 0
    )


def test_retrieval_tests_cover_the_questions_exec_must_answer_or_follow() -> None:
    tests = build_config.retrieval_tests(None)
    assert len(tests) == 97
    assert all(json.loads(t["vars"]["gold"]) for t in tests)
