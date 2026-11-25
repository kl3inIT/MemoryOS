# Chat citations draw the passage that matched

Issue [#954](https://github.com/kl3inIT/MemoryOS/issues/954).

## Problem

A Chat answer cites a `ChatSource` registered by `SearchTool.appendEvidence` for the whole window of passages
given to the model (`startOrdinal`..`endOrdinal`, the provenance of every passage). For a small document that
window is the whole document. The passage search matched (the selected section's anchor, `hit.ordinal`) was
not recorded, so the browser could not tell it from its neighbours:

- `chat-citation-selection.ts` used `startOrdinal` as the matching ordinal and the whole range as the cited
  text, and `matchingProvenance` returned every passage's provenance (the "matching" one first);
- `chat-document-evidence.tsx` and the preview dialog then gave the original the boxes of every passage, so a
  2-page text PDF was tinted on every block of both pages, and a Word or text original was asked to locate the
  whole window's text, which it often cannot;
- the narrow panel's original (`evidence-view-switch.tsx`) was not given the cited passage's heading trail,
  which the dialog passes to tell two identical passages apart.

A second, smaller defect sits in the same place: when two selected sections of one document fall into separate
windows, every window took the first section's hit, clamped to its own range, as its anchor, so its budget
trimmed toward the wrong passage.

## Decision (owner, 2026-11-25)

Behave as Search does ([search spec](../../../specs/search.md)): the opened original is given only the matching
passage's provenance and text; a text original locates that passage and the rail shows its state. The model
still receives the whole window; only what is drawn changes. Chat passes no question terms to the reader.

## Design

- `ChatSource` gains a nullable `matchingOrdinal`, inside `startOrdinal`..`endOrdinal`, on Document citations
  only. `ChatSource.document(...)` takes it; file and Web citations carry null.
- `SearchTool` keeps, per document, the anchor ordinal of each selected section in selection order; each window
  cites the first anchor inside it (always present: every window is the union of whole section windows), and
  trims its budget toward that passage, as before.
- Persistence: sources are the `chat_message.sources` JSON array (V34/V64), read with Jackson into the record.
  A missing member reads as null, so answers saved before this change stay readable with no migration
  (`ChatSourceLegacyJsonTest`).
- API: `ChatSource` (`ChatSourceResponse`) gains optional `matchingOrdinal`; `openapi.yml` and the Hey API
  client are regenerated. The browser schema accepts it on Document sources only, inside the range.
- Browser: `citationSelection` uses `fileLocation.ordinal ?? matchingOrdinal`; when one is known the match is
  that one passage and its provenance alone, so the original draws one passage (PDF boxes of that passage,
  located text in Word, Markdown and text originals, the row of a workbook) and the passage list marks it among
  its neighbours. A source without it keeps today's behaviour: the whole range.
  `EvidenceViewSwitch` passes the citations' heading trails to the original, as the dialog does.

## Regression test

`web/src/features/chat/sources/citation-locating.test.tsx` renders small synthetic originals (Word through
docx-preview, which runs under jsdom; Markdown; text; a workbook) with `FilePreview`, and a text PDF's page text
as pdf.js reads it, and runs every passage through the reader's locator in three shapes: a Search passage, a
Search section of three merged passages, and a Chat citation built through `citationSelection`. It asserts a
floor on the share placed exactly. The passages are `StructuredDocumentChunker` output for each fixture's
extraction, committed in `citation-fixtures/chunks.json` and checked by `CitationFixtureChunksTest` in
`memoryos-core`. The originals were written for this test, one text in five formats; their extraction JSON
(`memoryos-core/src/test/resources/citation-fixtures/`) is what each route records: Docling's headings,
paragraphs, labelled table and PDF regions; Tika's single paragraph for Markdown and text; the spreadsheet
extractor's addressed cells. Main's selection (this bug) fails the Chat rows of Word, PDF, Markdown and workbook; reverting #953 (#948) fails the Word and PDF
rows.

## Out of scope, found by the test

- A Markdown upload is read by Tika as one paragraph of the raw file, so its first passage keeps `#` and `|`
  marks the rendered Markdown does not show, and is never located.
- A table row read as labelled cells (`Khoản chi: …`) is never located in a rendered Word table; a sheet's
  header row is not a body row of the workbook view.

These are recorded in the test's floors; fixing them is separate work.
