/// <reference types="node" />
// It reads its fixture originals from disk, so it runs with Node's types; the app program has none of its own.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { passageBody, passageSection } from "@/features/documents/passage";
import { readSourceLocation } from "@/features/documents/source-provenance";
import { placeCitations } from "@/features/preview/citation-highlight";
import { FilePreview, type CitationConfidence } from "@/features/preview/file-preview";
import type { PreviewContent } from "@/features/preview/preview-content";
import type { Sheets } from "@/features/preview/preview-kind";
import { i18n } from "@/i18n/index";
import { citationSelection } from "./chat-citation-selection";
import { sourceSchema } from "./chat-evidence";

/*
 * Every passage of a few small originals, run through the locator the reader paints citations with, so a change
 * that stops citations being found in an original fails here rather than on staging. The originals are synthetic
 * (`citation-fixtures/`); their passages are the chunker's own output for their extraction, committed in
 * `chunks.json` and kept current by `CitationFixtureChunksTest` in memoryos-core, which says how to regenerate it.
 *
 * Each passage is cited three ways: alone, as Search gives the original its matching chunk; as part of a Search
 * section, three adjacent passages merged; and as a Chat citation of the window of up to five passages around it
 * that the model read, drawn through `citationSelection` as the Chat reader draws it.
 */

// The source reader first draws source plain, before its highlighter has loaded; that drawing is located here.
vi.mock("@/components/assistant-ui/elements/code-renderers.aui", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  HighlightedCode: ({ code }: { code: string }) => (
    <pre>
      <code>{code}</code>
    </pre>
  ),
}));

type Chunk = { ordinal: number; content: string; provenanceJson: string };
type Citations = { texts: string[]; sections: (string | undefined)[]; provenance: string[][] };
type Shape = "search" | "section" | "chat";

const fixture = (name: string) => join(import.meta.dirname, "citation-fixtures", name);
const text = (name: string) => readFileSync(fixture(name), "utf8");
const chunks = JSON.parse(text("chunks.json")) as Record<string, Chunk[]>;
const NAME = "quy-che-cong-tac-phi";

/** The passages `from`–`to` as the reader reads a cited range: bodies joined, under the first one's heading. */
function cited(all: readonly Chunk[], from: number, to: number, provenance: string[]) {
  const range = all.filter((chunk) => chunk.ordinal >= from && chunk.ordinal <= to);
  return {
    text: range.map((chunk) => passageBody(chunk.content)).join("\n"),
    section: passageSection(range[0]!.content),
    provenance,
  };
}

function citations(kind: string, shape: Shape): Citations {
  const all = chunks[kind]!;
  const last = all.length - 1;
  const each =
    shape === "search"
      ? all.map((chunk) => cited(all, chunk.ordinal, chunk.ordinal, [chunk.provenanceJson]))
      : shape === "section"
        ? all.slice(0, Math.max(1, all.length - 2)).map((chunk) =>
            cited(
              all,
              chunk.ordinal,
              Math.min(last, chunk.ordinal + 2),
              all.slice(chunk.ordinal, chunk.ordinal + 3).map((one) => one.provenanceJson),
            ),
          )
        : all.map((chunk) => {
            const window = all.slice(Math.max(0, chunk.ordinal - 2), chunk.ordinal + 3);
            const source = sourceSchema.parse({
              citationId: 1,
              documentId: "4f7f8a4e-8f43-4a8e-9b1f-0d6a1c2b3e4f",
              generation: "5a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
              title: NAME,
              startOrdinal: window[0]!.ordinal,
              endOrdinal: window.at(-1)!.ordinal,
              matchingOrdinal: chunk.ordinal,
              provenance: window.map(({ ordinal, provenanceJson }) => ({
                ordinal,
                provenanceJson,
              })),
            });
            const match = citationSelection(source).matches[0]!;
            return cited(
              all,
              match.matchingOrdinal,
              match.matchingEndOrdinal ?? match.matchingOrdinal,
              [...(match.provenance ?? [])],
            );
          });
  return {
    texts: each.map((one) => one.text),
    sections: each.map((one) => one.section),
    provenance: each.map((one) => one.provenance),
  };
}

/** Where the reader placed each citation in the original it rendered, in citation order. */
async function placedIn(content: PreviewContent, filename: string, cited: Citations) {
  const onPlaced = vi.fn<(found: readonly CitationConfidence[]) => void>();
  render(
    <FilePreview
      content={content}
      filename={filename}
      download="/original"
      citations={{
        texts: cited.texts,
        sections: cited.sections,
        rows: cited.provenance.map((provenance) => {
          const place = readSourceLocation(provenance);
          return place.row === undefined ? undefined : { sheet: place.sheet, row: place.row };
        }),
        onPlaced,
      }}
    />,
  );
  await waitFor(() => expect(onPlaced).toHaveBeenCalled(), { timeout: 5000 });
  return onPlaced.mock.lastCall![0];
}

/** A text PDF's pages as pdf.js reads their text, a block for each line, for the same locator. */
function placedInPdfText(cited: Citations) {
  const pages = JSON.parse(text(`${NAME}.pdf-text.json`)) as { page: number; lines: string[] }[];
  const layer = document.body.appendChild(document.createElement("div"));
  for (const { lines } of pages) {
    const page = layer.appendChild(document.createElement("div"));
    for (const line of lines) page.appendChild(document.createElement("div")).textContent = line;
  }
  return placeCitations(layer, cited.texts, cited.sections).map((placed) => placed.confidence);
}

/**
 * The least share of citations of each shape the locator must place exactly in each original: what it places
 * today. An approximate placement does not count: the anchored fallback finds most passages approximately even
 * when blocks run together (#948), so only the exact share falls when the locator regresses.
 * The passages it misses are a table row read as labelled cells (`Khoản chi: …`), which no rendered table holds
 * verbatim; the first Markdown passage, whose text keeps the file's `#` and `|` marks; and a sheet's header row,
 * which the workbook view renders as the column headings rather than as a row.
 */
const originals: {
  kind: string;
  filename: string;
  content?: () => PreviewContent;
  floor: Record<Shape, number>;
}[] = [
  {
    kind: "docx",
    filename: `${NAME}.docx`,
    content: () => ({ kind: "docx", blob: new Blob([readFileSync(fixture(`${NAME}.docx`))]) }),
    floor: { search: 0.78, section: 0.58, chat: 0.78 },
  },
  {
    kind: "pdf",
    filename: `${NAME}.pdf`,
    floor: { search: 0.78, section: 0.58, chat: 0.78 },
  },
  {
    kind: "markdown",
    filename: `${NAME}.md`,
    content: () => ({ kind: "markdown", text: text(`${NAME}.md`), truncated: false, bytes: 0 }),
    floor: { search: 0.5, section: 0, chat: 0.5 },
  },
  {
    kind: "text",
    filename: `${NAME}.txt`,
    content: () => ({ kind: "text", text: text(`${NAME}.txt`), truncated: false, bytes: 0 }),
    floor: { search: 1, section: 1, chat: 1 },
  },
  {
    kind: "xlsx",
    filename: `${NAME}.xlsx`,
    content: () => ({ kind: "xlsx", sheets: JSON.parse(text(`${NAME}.sheets.json`)) as Sheets }),
    floor: { search: 0.75, section: 0.5, chat: 0.75 },
  },
];

const share = (
  found: readonly CitationConfidence[],
  counted: (one: CitationConfidence) => boolean,
) => found.filter(counted).length / found.length;

beforeEach(async () => {
  await i18n.changeLanguage("vi");
  // jsdom has no layout; a located passage is scrolled to in a real browser.
  Element.prototype.scrollIntoView = vi.fn();
  // Nor constructed stylesheets, which the Word reader adopts docx-preview's styles as; the text is the same.
  document.adoptedStyleSheets = [];
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(Element.prototype, "scrollIntoView");
  Reflect.deleteProperty(document, "adoptedStyleSheets");
});

describe("the passages of each fixture original are located where the reader draws them", () => {
  for (const original of originals) {
    for (const shape of ["search", "section", "chat"] as const) {
      it(`${original.kind}, cited as a ${shape} citation`, async () => {
        const cited = citations(original.kind, shape);
        const found = original.content
          ? await placedIn(original.content(), original.filename, cited)
          : placedInPdfText(cited);
        const exact = share(found, (one) => one === "exact");
        const located = share(found, (one) => one !== "none");
        console.info(
          `${original.kind} ${shape}: ${exact.toFixed(2)} exact, ${located.toFixed(2)} located`,
          JSON.stringify(found),
        );
        expect(found).toHaveLength(cited.texts.length);
        expect(exact).toBeGreaterThanOrEqual(original.floor[shape]);
      });
    }
  }
});
