# Plan

- [x] Design: layers, metrics, dataset schema, references (Onyx search_quality, Chroma chunking_evaluation, LightRAG/RAGAS).
- [ ] Decide benchmark identity, corpus and judge model (open decisions in the design).
- [ ] `tools/rag-benchmark/` uv project: dataset schema (JSONL), config, run manifest, a synthetic public sample.
- [ ] Corpus export: canonical extractions of the benchmark tenant's current documents to `.tmp/rag-benchmark/corpus/`.
- [ ] Case generation (Chroma pipeline with filters) and a review file; only reviewed cases are scored.
- [ ] Chunking layer: current structured chunks, packed variants (256 floor; 512/768/1,024 ceilings), fixed-token baselines.
- [ ] Search layer: `/api/search` hit@k, MRR, excerpt token recall/precision, latency.
- [ ] Chat layer: session per case; RAGAS FactualCorrectness and Faithfulness, citation precision, abstention, tokens, cost, latency.
- [ ] Deep research layer on multi-part cases (optional flag).
- [ ] Report: summary by layer and category, baseline comparison with per-case changes.
- [ ] First baseline run on staging; record summary numbers (no content) in `docs/tests/`.
