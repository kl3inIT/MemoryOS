# Native Workspace and table readers

Status: MEM-63 is In Progress with `nhuxuanviet27102004`, reconciled 2026-09-08. This is the native-reader contract for the Google Drive integration branch, not a claim that readers are merged into main or accepted against Tasco data. Reader/fixture work can proceed using the delivered MEM-61 artifact contract; Google integration is exercised with MEM-9/MEM-10.

Tracking: [MEM-63](https://linear.app/memory-os/issue/MEM-63), child of [MEM-60](https://linear.app/memory-os/issue/MEM-60). Shares the MEM-61 canonical output contract; service deployment is not required to read Sheets.

## Goal

Implement native content acquisition for Google Sheets and Google Docs inside the Google Drive Source, plus reusable Java XLSX/CSV readers. Reuse the existing TXT/Markdown adapter where its contract is sufficient. Do not create a separate Sheets connector or run native structured data through OCR.

Use the [current Document contract](../../../specs/document.md): publish current metadata and artifact reference without DocumentVersion history, processing-profile uniqueness or stored PostgreSQL normalized text. The existing canonical schema has heading/table/page-bounding-box provenance; add native Sheet ranges and Doc element references compatibly with fixtures rather than assuming those fields already exist. Actual Tasco source data is unavailable; provider tests use synthetic documents.

## Work and acceptance

- [ ] Read supported Google Sheets tabs with stable sheet IDs/ranges, row/column coordinates, typed/effective/formatted values and formulas required for audit. Preserve merged/header context, blanks and locale/timezone metadata. Never execute imported spreadsheet formulas locally as arbitrary code.
- [ ] Detect edits across acquisition and retry or report an inconsistent snapshot; do not claim cross-request transactional Google snapshots.
- [ ] Read Google Docs tabs, headings, paragraphs, lists and tables with source element references; unsupported content is explicit rather than silently presented as complete.
- [ ] Implement bounded Java XLSX/CSV readers preserving tabular semantics and native TXT/Markdown paths; enforce decompression, encoding, cell/row/output and time limits.
- [ ] Persist original API snapshots/exports and canonical extraction artifacts through shared object lifecycle; keep Drive file identity independent of content hash.
- [ ] Integrate actual shared-with-me Sheets and Docs through SOURCE_SYNC -> INGESTION -> Document plus authorized read, under MEM-10 ownership.
- [ ] Test two-tab Sheets with dates/currency/formulas/merged headers, repeated reads, unchanged version, mid-read modification, missing scope, quota errors and permission loss.
- [ ] Prove range/element provenance survives canonical conversion and is consumable by structured chunking. Exact numeric calculations are not delegated to embeddings.
- [ ] Run recorded-response tests and a sanitized live Tasco-equivalent Google account proof. Real customer contents are never committed.

Exclusions: search/answer generation, formula computation engine, Google Group expansion, a separate Google Sheets source-management surface and Docling parsing of Sheets.

Repository plan: `docs/increments/active/google-drive-structured-ingestion/plan.md`.
