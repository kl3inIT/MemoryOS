import { describe, expect, it } from "vitest";
import { readSourceLocation } from "@/features/documents/source-provenance";
import { citationSelection } from "./chat-citation-selection";
import { sourceSchema } from "./chat-evidence";

// A 2-page text PDF whose whole text is the window of five passages Chat gave the model (ordinals 3–7); search
// matched passage 5. Each passage records the region of its own block.
const box = (page: number, top: number) =>
  JSON.stringify({
    page_no: page,
    bbox: { l: 72, t: top, r: 520, b: top - 40, coord_origin: "BOTTOMLEFT" },
  });
const window = [
  { ordinal: 3, provenanceJson: box(1, 760) },
  { ordinal: 4, provenanceJson: box(1, 600) },
  { ordinal: 5, provenanceJson: box(1, 440) },
  { ordinal: 6, provenanceJson: box(2, 760) },
  { ordinal: 7, provenanceJson: box(2, 600) },
];

function cited(matchingOrdinal?: number) {
  const source = sourceSchema.parse({
    citationId: 1,
    documentId: "4f7f8a4e-8f43-4a8e-9b1f-0d6a1c2b3e4f",
    generation: "5a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
    fileId: null,
    fileLocation: null,
    web: null,
    title: "quy-che-cong-tac-phi.pdf",
    startOrdinal: 3,
    endOrdinal: 7,
    ...(matchingOrdinal === undefined ? {} : { matchingOrdinal }),
    provenance: window,
    mediaType: "application/pdf",
    sourceTypes: ["FILE"],
    providerUrl: null,
  });
  const match = citationSelection(source).matches[0]!;
  const drawn = readSourceLocation(match.provenance ?? []);
  return {
    range: [match.matchingOrdinal, match.matchingEndOrdinal],
    pages: drawn.pages,
    boxes: drawn.boxes.map((region) => `${region.page}:${region.top}`),
  };
}

describe("a Chat citation's original", () => {
  it("draws only the passage search matched, as Search does, not the window the model read", () => {
    const drawn = cited(5);
    console.info("Chat citation of window 3–7 matching 5 draws", JSON.stringify(drawn));
    expect(drawn).toEqual({ range: [5, 5], pages: [1], boxes: ["1:440"] });
  });

  it("opens an answer saved before the match was recorded as it did: the whole cited range", () => {
    const drawn = cited();
    console.info("Saved citation of window 3–7 without a match draws", JSON.stringify(drawn));
    expect(drawn).toEqual({
      range: [3, 7],
      pages: [1, 2],
      boxes: ["1:760", "1:600", "1:440", "2:760", "2:600"],
    });
  });

  it("refuses a match outside the cited range", () => {
    expect(() => cited(8)).toThrow();
  });
});
