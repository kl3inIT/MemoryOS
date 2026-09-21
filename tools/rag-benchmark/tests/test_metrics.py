from rag_benchmark import metrics


def test_recall_counts_gold_documents_within_the_cutoff() -> None:
    ranked = ["a", "b", "c", "d", "e", "f"]
    assert metrics.recall_at(ranked, ["a", "f"], 5) == 0.5
    assert metrics.recall_at(ranked, ["a", "f"], 10) == 1.0
    assert metrics.recall_at(ranked, [], 5) == 0.0


def test_ndcg_rewards_the_higher_rank_and_saturates_when_every_gold_document_leads() -> None:
    top = metrics.ndcg_at(["a", "b", "c"], ["a"], 5)
    lower = metrics.ndcg_at(["b", "c", "a"], ["a"], 5)
    assert top == 1.0
    assert 0 < lower < top
    assert metrics.ndcg_at(["a", "b"], ["a", "b"], 5) == 1.0


def test_citation_scores_separate_completeness_from_noise() -> None:
    assert metrics.citation_recall(["a"], ["a", "b"]) == 0.5
    assert metrics.citation_precision(["a", "x"], ["a", "b"]) == 0.5
    # A reply that cited nothing has no precision to report rather than a perfect one.
    assert metrics.citation_precision([], ["a"]) == 0.0


def test_abstention_is_declining_without_reaching_forbidden_evidence() -> None:
    assert metrics.abstained("Tôi không tìm thấy thông tin này trong tài liệu.", [])
    # The wording the staging replies actually used, which the first marker list missed.
    assert metrics.abstained("Tôi chưa tìm thấy tài liệu nào nói về việc này.", [])
    assert metrics.abstained("I could not find any document covering that.", [])
    # Declining while pointing at a document the actor may read is still a refusal to answer.
    assert metrics.abstained("Chưa tìm thấy số liệu đó; tài liệu gần nhất là quy chế [1].", ["a"])
    # Reaching a forbidden document is never an abstention, whatever the wording.
    assert not metrics.abstained("Không tìm thấy, nhưng theo tài liệu [1] thì...", ["a"], ["a"])
    assert not metrics.abstained("Doanh thu là 12 tỷ.", [])


def test_percentile_uses_the_nearest_rank() -> None:
    assert metrics.percentile([1.0, 2.0, 3.0, 10.0], 0.5) == 2.0
    assert metrics.percentile([1.0, 2.0, 3.0, 10.0], 0.9) == 10.0
    assert metrics.percentile([], 0.5) == 0.0
