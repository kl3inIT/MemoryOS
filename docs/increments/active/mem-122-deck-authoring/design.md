# MEM-122 — Presentation authoring from a deck plan

[MEM-122](https://linear.app/memory-os/issue/MEM-122) · builds on [MEM-110](../../completed/mem-110-memoryos-interpreter/design.md) (interpreter) and [MEM-111](../../completed/mem-111-generated-file-preview/design.md) (artifact preview) · research: [HTML slide decks](html-deck-research.md)

## Problem

A user attaches reports, or points at indexed Sources, and asks for a slide deck in one message. Chat can already produce a `.pptx`: `search_knowledge` grounds the answer and stages the source originals into the sandbox, `run_python` has `python-pptx`, and the resulting file becomes a `chat_file_artifact` with a download card, a LibreOffice-rendered preview and a `/library` entry.

What is missing is the part that decides whether the deck is presentable. The model writes layout code blind — it cannot see the file it just wrote — so the result is whatever geometry it guessed: text running off the slide, 1 pt differences between slide titles, fourteen bullets on one slide, no footer, no sources. `ChatPrompts` states the `.docx` structure rules and enforces them with `check-docx`, but says nothing at all about `.pptx`.

The failure mode is the same one `check-docx` was built for, one step worse: a Word document with flat paragraphs is ugly but readable, a slide with overflowing text is unusable, and the model has no way to notice.

## Decision

Split authoring from layout, the way [template-driven generation](https://arxiv.org/pdf/2402.14871) does: the model owns content, the renderer owns the deck.

- The model writes a **deck plan**: a strict JSON document of typed slides (`title`, `agenda`, `section`, `bullets`, `columns`, `metrics`, `table`, `image`, `quote`, `closing`) plus deck-level identity (title, author, date, confidentiality, theme) and a `sources` list.
- `render-deck plan.json deck.pptx` validates the plan and renders it against a fixed corporate template: one 16:9 geometry, one type ramp, one palette per theme, a title rule, a footer carrying deck title, confidentiality and slide number, and an automatically appended **Nguồn/Sources** slide whenever the plan carries sources.
- Text that cannot fit is not rendered small and silently: every text box is fitted by a fixed size ladder, and a field that still does not fit at the smallest size **fails the render** naming the slide, the field and the character budget. A deck either comes out correct or does not come out.
- `check-pptx deck.pptx` is the gate for any presentation, including one the model wrote by hand with `python-pptx`: it reports overflowing text, empty slides, a slide with nothing large enough to read as a title, sub-10 pt text, leftover placeholder text, more than seven bullets in one box, shapes lying partly outside the slide, stretched pictures and decks not built by `render-deck`.

This keeps the whole delivered spine untouched — execution, artifact storage, `file_link`, the preview modal, `/library`, the cleanup sweep — and adds only what is absent: the authoring layer. It follows the executor's established shape (`recalc-xlsx`, `check-docx`, `pptx-to-pdf`): a Python CLI in the image, named in the `run_python` guidance, with its result printed as one JSON line per file.

### Why not a JVM renderer or an external deck service

- A server-side PPTX writer would need a new dependency (POI is used read-only here) and would have to re-implement staging, sandboxing and the 60 s budget that `run_python` already owns. `UsageReportPdf` shows what a hand-written layout engine costs.
- An external generator mounted over MCP ([MEM-112](../mem-112-chat-mcp-client/design.md)) returns a third-party payload, not a `chat_file_artifact`: no download card, no preview, no library entry, and its bytes escape `JdbcChatArtifactCleanupRepository`. Refused under [ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md).

## Contract

### Deck plan

One JSON object. Unknown keys are refused rather than ignored, so a misspelled field never silently drops content.

| Field | Required | Meaning |
| --- | --- | --- |
| `title` | yes | Deck title; the title slide headline and the footer's left text |
| `subtitle`, `author`, `date`, `confidentiality` | no | Title slide identity; `confidentiality` also sits in every footer |
| `theme` | no | `corporate` (default), `slate`, `emerald` |
| `language` | no | `vi` (default) or `en`; selects the built-in labels (`Nguồn`/`Sources`, `Trang`/`Slide`) |
| `sources` | no | `[{label, detail?}]`; renders the closing sources slide and is what binds a deck to its evidence |
| `slides` | yes | 1–40 typed slides |

Slide types and their fields are listed in [the plan module's docstring](../../../../interpreter/executor/memoryos_deck/plan.py); every type takes an optional `note` (speaker notes) and `cites` (1-based indexes into `sources`, rendered as a footnote under the body).

Caps are part of the contract, not advice: 7 bullets a slide, 2 columns, 2–4 metrics, 6 table columns, 12 table rows, 8 agenda items, 40 slides. Exceeding one is an error naming the slide, because the honest fix is another slide.

### Results

`render-deck` prints one JSON line per output: `{file, rendered: true, slides, warning_count, warnings}`, or `{file, rendered: false, error_count, errors}` with exit 1 and no file written. A warning is a judgement the renderer will not force — a deck with no sources, a 250-character bullet, five bullet slides in a row — while an error is something it cannot lay out.
`check-pptx` prints one JSON line per file — `{file, checked, slides, built_with_render_deck, issue_count, issues}` — and, exactly as `check-docx` does, exits non-zero only when a file could not be read: a fault is a report the model acts on, not a failed command.

### Output formats

The output suffix picks the renderer and one call may name both: `render-deck plan.json deck.pptx deck.html`.

- `.pptx` is the editable deck, for presenting and for the Chat preview (`pptx-to-pdf`).
- `.html` is the fast path: no LibreOffice, opens anywhere, one self-contained file — inline CSS, pictures as data URIs, no network, since the executor has none. It is the same template placed at the same inch coordinates, so it is the `.pptx` in another form rather than a second design, and `@page` prints it to 960 x 540 pt, the page box LibreOffice produces from the `.pptx`.

Both share plan validation and text fitting, so a plan refused for one format is refused for the other, and the HTML needs no separate gate: nothing can overflow a box the renderer refused to fill.

### Model guidance

`ChatPrompts.RUN_PYTHON_GUIDANCE` gains the presentation block: build a `.pptx` by writing a deck plan and calling `render-deck`, not by placing shapes; run `check-pptx` afterwards and fix what it reports; put figures on `image` slides by saving a matplotlib PNG first; cite with `sources` + `cites` rather than typing footnotes.

## Non-goals

- No Word or Excel template system; `check-docx` and `recalc-xlsx` keep those formats.
- No theme editor, no Tenant branding storage, no logo upload. Themes are the three built-in palettes until a Tenant-branding issue exists.
- No new tool, endpoint, table, SSE event or frontend code. A rendered deck is an ordinary generated file.
- No automatic outline agent: the model plans the deck inside its own turn, as it already plans code.

## Verification

- `interpreter/service/tests/integration_tests/test_office_stack.py` covers both CLIs against the real executor image, as `check-docx` and `recalc-xlsx` are covered: a full Vietnamese deck renders and passes `check-pptx`, an overflowing plan fails the render instead of producing a broken slide, an unknown field is refused, and a hand-built deck's faults are reported.
- `ChatWebPromptsTest` covers the guidance lines.
- Visual check: `pptx-to-pdf` the rendered deck and read the pages.
