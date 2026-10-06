# Brief: drafting benchmark questions for one department (MEM-230 P4)

You write candidate questions for a retrieval-augmented chat benchmark over public disclosures of Savico (SVC,
a Vietnamese listed company). The questions measure a system you must NOT look at: do not query MemoryOS, do not
read `document_chunks`, do not look at any MemoryOS answer. Work only from the files named here.

## Inputs

- Your department's drawn pages: entries of `D:/MemoryOS-mem230/tools/rag-benchmark/corpus/sample.json` whose
  `department` is yours. Each has `document`, `page`, `kind` (`prose`, `table`, `image`).
- Page files: `C:/Users/admin/.memoryos-bench/pages/<document>/<NNN>.txt` (text layer) and, for `kind: image`,
  `C:/Users/admin/.memoryos-bench/pages/<document>/<NNN>.png` — READ THE PNG with your Read tool; the .txt of an
  image page is empty or garbage.
- Document metadata (id, period, kind): `D:/MemoryOS-mem230/tools/rag-benchmark/corpus/manifest.json`.
- You may read OTHER pages of your department's documents, and Haxaco (`hax-…`) pages, only to (a) confirm an
  answer is unique, (b) build temporal / multi-hop / near-miss questions. Every question still starts from a drawn
  page: at least one evidence item must be a drawn page.
- Old candidate questions (written from an older system's chunks — biased): 
  `tools/rag-benchmark/datasets/questions.jsonl` (removed in P6; in git history before it). You may re-author an old question only if its
  fact is on one of your drawn pages; re-read the page and rewrite it from scratch. Otherwise ignore them.

## Quota for your department (answerable questions use only your department's Savico documents)

| category | count | meaning |
| --- | --- | --- |
| lookup | 6 | one fact from one passage |
| temporal | 2 | needs the right period/version among several documents of the same kind (e.g. which quarter, which year's AGM) |
| aggregate | 2 | combine or compute over several figures (sum, difference, growth %, count) — state the computed gold |
| multi_hop | 2 | needs two passages (two pages or two documents) of your department |
| absent | 2 | plausible for this department but stated nowhere in the corpus — check by searching the page texts (`grep -ri`) |
| near_miss | 2 | the fact exists only for another period or only for Haxaco, so a careless system answers with the wrong document |
| false_premise | 1 | the question assumes something the documents contradict |

Spread questions over different documents; no more than 3 questions from one document. If a drawn page holds
nothing worth asking (cover page, signatures), skip it and say so in your report.

## Writing rules (each one prevents a bias)

1. **User voice, not document wording.** Write as an employee would type: abbreviations (BCTC, ĐHĐCĐ, HĐQT, BKS,
   TGĐ, LNST), periods as people say them ("quý 1 năm nay", "Q2/2026", "năm ngoái" only with `as_of`), at least 3
   of your 17 questions without Vietnamese diacritics, 1–2 with a small typo. Do NOT copy phrases of 4+ words from
   the quote.
2. **Evidence is verbatim from the original page**: `quote` is an exact substring of the page text; for an image page
   transcribe exactly what the image shows (keep the document's number format, e.g. `6.445.238.783.576`). Keep
   quotes short (one sentence or one table row with its header context).
2b. **Quotes must stand alone.** A reader who sees only the quotes (each labelled with company, document kind and
   period) must be able to reach the gold answer. So a table quote carries the row label AND the column headers or
   the column's date (e.g. "Chỉ tiêu | 31/3/2026 | 01/01/2026" then the row), and every fact in the gold answer —
   every figure, the period, what the figure is — appears in some quote. A bare number is never a quote. If the
   answer needs a second page, add it as a second evidence item. (Blind validators rejected 7 of 17 first-batch
   questions for exactly this.)
3. **Numbers on image pages**: read the figure twice from the PNG; if any digit is uncertain, do not use it.
4. **Gold answer**: the shortest complete answer, in Vietnamese, with units and period; for aggregates show the
   computation. For absent/near_miss/false_premise: what a correct reply must do (decline, or correct the premise,
   or name the right period) — and for near_miss name the tempting wrong document.
5. **One right answer.** If another document in the corpus could give a different answer (e.g. a later notice
   superseding an earlier one), either make the question specific (period, document date) or set `as_of` and give
   the answer valid on that date.
6. `as_of` (YYYY-MM-DD) is required whenever the question says "hiện tại", "mới nhất", "năm nay", "gần đây".

## Output

Write `C:/Users/admin/.memoryos-bench/drafts/<department>.jsonl`, one JSON object per line:

```json
{"id": "<department>-001", "department": "<department>", "category": "lookup", "question": "...",
 "as_of": null, "evidence": [{"document": "svc-...", "page": 12, "quote": "..."}],
 "gold_answer": "...", "passage_type": "table|prose|list|image-table|image-prose",
 "tempting_wrong_document": null, "drafted_by": "claude-opus-5-5", "notes": "why this has one right answer"}
```

Then reply with: counts per category, the drawn pages you skipped and why, and any page whose image you could not
read reliably. Do not write anywhere else.
