import type { Root } from "mdast";
import { describe, expect, it } from "vitest";

import { remarkCitations, sourcesSchema } from "./chat-evidence";

const document = {
  citationId: 1,
  documentId: "00000000-0000-4000-8000-000000000001",
  generation: "00000000-0000-4000-8000-000000000002",
  title: "Leave policy",
  startOrdinal: 0,
  endOrdinal: 0,
  provenance: [{ ordinal: 0, provenanceJson: "{}" }],
};

describe("provider links", () => {
  it("keeps an https link of any provider", () => {
    for (const providerUrl of [
      "https://drive.google.com/open?id=1AbCdEfGhIjKlMnOp",
      "https://contoso.sharepoint.com/sites/HR/Shared%20Documents/Leave.docx",
    ]) {
      expect(sourcesSchema.parse([{ ...document, providerUrl }])[0]).toMatchObject({ providerUrl });
    }
  });

  it("rejects a link that is not https or carries credentials", () => {
    for (const providerUrl of [
      "http://contoso.sharepoint.com/a.docx",
      "javascript:alert(1)",
      "https://user:secret@contoso.sharepoint.com/a.docx",
    ]) {
      expect(sourcesSchema.safeParse([{ ...document, providerUrl }]).success).toBe(false);
    }
  });
});

describe("citations without a count cap", () => {
  it("accepts more than 24 sources", () => {
    const sources = Array.from({ length: 30 }, (_, index) => ({
      citationId: index + 1,
      documentId: "00000000-0000-4000-8000-000000000001",
      generation: "00000000-0000-4000-8000-000000000002",
      title: `Document ${index + 1}`,
      startOrdinal: 0,
      endOrdinal: 0,
      provenance: [{ ordinal: 0, provenanceJson: "{}" }],
    }));
    expect(sourcesSchema.parse(sources)).toHaveLength(30);
  });

  it("links citation numbers past 24", () => {
    const tree: Root = {
      type: "root",
      children: [{ type: "paragraph", children: [{ type: "text", value: "See [30] and [0]." }] }],
    };
    remarkCitations()(tree);
    const paragraph = tree.children[0];
    if (paragraph?.type !== "paragraph") throw new Error("paragraph expected");
    const links = paragraph.children.filter((node) => node.type === "link");
    expect(links.map((link) => link.url)).toEqual(["#citation-30"]);
  });
});
