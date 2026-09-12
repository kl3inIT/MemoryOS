---
name: pe-diagram-docx
description: Capture Visual Paradigm answers, place them into a supplied PE DOCX template, write brief student-like descriptions, and render every page for submission QA. Use for practical-exam workflows where the source of truth is a .vpp file and the deliverable is a completed .docx containing diagram screenshots and short tables or explanations.
---

# PE Diagram DOCX

Complete the provided template without redesigning it. Keep the `.vpp` project as the editable source of truth and use clean canvas screenshots when the Visual Paradigm edition adds a watermark to native exports.

## Workflow

1. Read the exam paper and template before editing.
2. List every required answer, diagram type, actor/entity, relationship, table, and personal field.
3. Open the existing `.vpp`; do not recreate a diagram that has already been verified.
4. Validate syntax and business coverage before capture.
5. Fit the complete diagram in the canvas. Keep labels readable, connectors separated, and shapes close to their content.
6. Capture the application window and crop to the diagram canvas with `scripts/crop_vp_canvas.py`.
7. Prepare a JSON manifest and fill a copy of the template with `scripts/fill_pe_docx.py`.
8. Render the completed DOCX with `scripts/render_docx_word.py`.
9. Inspect every rendered page at full size. Fix clipping, overflow, tiny text, blank pages, watermarks, and obvious template placeholders.
10. Preserve the original template and deliver a separately named completed DOCX.

## Multiple papers

When the user provides two or more independent papers and explicitly wants parallel work:

1. Inspect the shared rubric and output convention in the main agent.
2. Give each paper or student package its own output directory.
3. Spawn one sub-agent per independent package, up to the available concurrency limit.
4. Do not let two agents edit the same `.vpp`, DOCX, manifest, or output file.
5. Give each package its own natural wording profile. Vary sentence structure and emphasis while preserving the exact business meaning and diagram terminology.
6. Have the main agent review the diagrams, rendered pages, filenames, student metadata, and duplicate descriptions before delivery.

Do not spawn sub-agents for a single paper or when the user did not request parallel work.

Do not create artificial synonym swaps. A useful set of variants is:

- action-first: "Checks booking and payment status."
- purpose-first: "Lets the guest follow the booking and payment status."
- record-first: "Shows the latest booking and payment status to the guest."

Keep actor, use-case, entity, relationship, and constraint names unchanged. If several students share one diagram, use a separate manifest per student or expose every description as a per-job variable; never reuse the same completed description table unchanged.

## Diagram gate

- Use the native notation requested by the paper.
- Check direction and meaning of every relationship, not only its appearance.
- Use noun phrases for context-diagram data flows.
- Keep use-case names as verb phrases and actors outside the system boundary.
- Use `<<include>>` only for mandatory reused behavior and `<<extend>>` only for optional or conditional behavior.
- Keep conceptual ERD entities free of attributes unless the paper asks for them.
- Use one shared `.vpp` project unless the paper explicitly requires separate projects.

## Screenshot gate

Prefer a manual canvas screenshot when native export adds an Evaluation/Community watermark. Exclude menus, palettes, scrollbars, selection handles, cursors, and status bars. Do not crop away external actors, relationship labels, multiplicities, or the diagram title when the title is part of the answer.

Example:

```powershell
python scripts/crop_vp_canvas.py `
  --input vp-window.png --output q2-context.png `
  --canvas 270,250,2270,1220 --trim --padding 24
```

## DOCX gate

Treat the supplied template as authoritative. Retain its page size, margins, headers, footers, numbering, and table style. Replace placeholders rather than appending a new report. Never invent the student's name, ID, campus, or exam date.

Convert blue or otherwise highlighted instruction placeholders to normal black answer text. Use manifest paragraph alignment when identity lines must share the same left, center, or right edge.

Load `references/human-style.md` before drafting explanations. Keep each table cell to one direct sentence where possible.

Example:

```powershell
python scripts/fill_pe_docx.py `
  --template exam_template.docx `
  --manifest answer.json `
  --variables student.json `
  --output exam_completed.docx
```

The manifest supports:

- `paragraph_replacements`: exact-text or substring replacement.
- `images`: replace a paragraph containing a marker with one image.
- `tables`: replace all data rows of a table while preserving its header row.

Manifest strings may contain `${student_name}`, `${student_id}`, `${campus}`, `${exam_date}`, or any other key supplied by `--variables`.

Descriptions can also be variables such as `${guest_description}` or `${booking_description}`. This lets each batch job use different natural wording without changing the diagram itself.

For several students using the same answer manifest:

```powershell
python scripts/batch_fill_pe_docx.py --batch batch.json --workers 4
```

Example batch file:

```json
{
  "template": "exam_template.docx",
  "manifest": "answer.json",
  "jobs": [
    {
      "variables": {
        "student_name": "Student One",
        "student_id": "SE123456",
        "campus": "Hanoi",
        "exam_date": "July 30, 2026"
      },
      "output": "out/SE123456.docx"
    }
  ]
}
```

Keep student metadata in a local batch file. Do not commit real student records to a public repository.

## Visual QA

On Windows with Microsoft Word installed:

```powershell
python scripts/render_docx_word.py `
  --input exam_completed.docx `
  --output-dir build/docx-qa
```

Review every `page-*.png`. A successful script run is not sufficient evidence that the document is visually correct.
