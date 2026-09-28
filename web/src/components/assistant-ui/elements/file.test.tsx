import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { documentKind } from "@/features/documents/document-source-presentation";
import { File } from "./file";

function glyph(filename: string, mimeType = "application/octet-stream") {
  const { container } = render(<File.Icon mimeType={mimeType} filename={filename} />);
  return container.querySelector("[data-slot=document-kind-icon]")?.getAttribute("data-kind");
}

describe("file icons", () => {
  it("use the search and source file-type glyphs, deciding by name for an octet-stream file", () => {
    expect(glyph("Báo cáo Q3 2026.xlsx")).toBe("spreadsheet");
    expect(glyph("doanh-thu-chi-tiet.csv", "text/csv")).toBe("spreadsheet");
    expect(glyph("Trình bày Q3 2026.pptx")).toBe("presentation");
    expect(glyph("Báo cáo Q3 2026.docx")).toBe("document");
    expect(glyph("Tài chính Q3.pdf", "application/pdf")).toBe("pdf");
    expect(glyph("phan_tich.py", "text/x-python")).toBe("code");
    expect(glyph("bieu_do.png", "image/png")).toBe("image");
    expect(glyph("du-lieu.bin")).toBe("generic");
  });

  it("lets the media type win over the name", () => {
    expect(documentKind("application/pdf", "bao-cao.xlsx")).toBe("pdf");
    expect(documentKind(null, "bao-cao.XLSX")).toBe("spreadsheet");
    expect(documentKind(undefined, undefined)).toBe("generic");
    expect(documentKind("text/plain", "phan_tich.py")).toBe("code");
    expect(documentKind("text/plain", "ghi-chu.txt")).toBe("text");
    expect(documentKind("text/plain", "ghi-chu")).toBe("text");
  });
});
