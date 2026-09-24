import { describe, expect, it } from "vitest";
import { fileTypeOf } from "./file-types";

describe("fileTypeOf", () => {
  it("names uploaded files by extension, whatever its case", () => {
    expect(fileTypeOf({ name: "Report.PDF" }).label).toBe("PDF");
    expect(fileTypeOf({ name: "plan.docx" }).label).toBe("Word");
    expect(fileTypeOf({ name: "sales.xlsx" }).label).toBe("Excel");
    expect(fileTypeOf({ name: "rows.jsonl" }).label).toBe("JSON Lines");
    expect(fileTypeOf({ name: "notes.md" }).label).toBe("Markdown");
  });

  it("names Google-native items by MIME type and falls back to the media family", () => {
    expect(
      fileTypeOf({ name: "Budget", mimeType: "application/vnd.google-apps.spreadsheet" }).label,
    ).toBe("Google Sheets");
    expect(fileTypeOf({ name: "Team", mimeType: "application/vnd.google-apps.folder" }).label).toBe(
      "Folder",
    );
    expect(fileTypeOf({ name: "scan", mimeType: "image/png" }).label).toBe("Image");
    expect(fileTypeOf({ name: ".env" }).label).toBe("File");
    expect(fileTypeOf({ name: null }).label).toBe("File");
  });
});
