// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { documentKind, friendlyMediaType } from "./document-source-presentation";

describe("document source presentation", () => {
  it("uses friendly labels without exposing unknown raw MIME values", () => {
    expect(friendlyMediaType("application/pdf")).toBe("PDF");
    expect(friendlyMediaType("application/pdf; charset=utf-8")).toBe("PDF");
    expect(
      friendlyMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ).toBe("Word document");
    expect(friendlyMediaType("image/png")).toBe("Image");
    expect(friendlyMediaType("application/x-private-format")).toBe("Document");
  });

  // Office, Google-native, text and image media types, with the file name deciding only when the
  // media type does not.
  it.each([
    ["application/pdf", undefined, "pdf"],
    ["application/vnd.google-apps.document", undefined, "document"],
    ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", undefined, "spreadsheet"],
    ["text/csv; charset=utf-8", undefined, "spreadsheet"],
    ["application/vnd.google-apps.presentation", undefined, "presentation"],
    ["text/markdown", undefined, "text"],
    ["image/png", undefined, "image"],
    ["application/zip", undefined, "generic"],
    [undefined, undefined, "generic"],
    ["application/pdf", "bao-cao.xlsx", "pdf"],
    [null, "bao-cao.XLSX", "spreadsheet"],
    ["text/plain", "phan_tich.py", "code"],
    ["text/plain", "ghi-chu.txt", "text"],
    ["text/plain", "ghi-chu", "text"],
  ] as const)("treats %s named %s as %s", (mediaType, filename, kind) => {
    expect(documentKind(mediaType, filename)).toBe(kind);
  });
});
