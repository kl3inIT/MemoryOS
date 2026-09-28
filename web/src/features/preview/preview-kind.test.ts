import { describe, expect, it } from "vitest";
import { parseCsv, previewKind, sanitizeDocxInPlace } from "./preview-kind";

describe("generated file preview", () => {
  it("picks the first Onyx variant that matches", () => {
    const xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    const docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    expect(previewKind("phân tích.py", "application/octet-stream")).toBe("code");
    expect(previewKind("data.json", "application/json")).toBe("code");
    expect(previewKind("chart.png", "image/png")).toBe("image");
    expect(previewKind("chart.svg", "image/svg+xml")).toBe("unsupported");
    expect(previewKind("báo cáo.pdf", "application/pdf")).toBe("pdf");
    expect(previewKind("doanh thu.csv", "text/csv")).toBe("csv");
    expect(previewKind("doanh thu.xlsx", xlsx)).toBe("xlsx");
    expect(previewKind("README.md", "text/markdown")).toBe("markdown");
    expect(previewKind("báo cáo.docx", docx)).toBe("docx");
    expect(previewKind("cũ.doc", "application/msword")).toBe("doc");
    expect(previewKind("notes.txt", "text/plain")).toBe("text");
    expect(previewKind("slides.pptx", "application/vnd.ms-powerpoint")).toBe("pptx");
    expect(previewKind("slides.ppt", "application/vnd.ms-powerpoint")).toBe("unsupported");
  });

  it("reads quoted CSV cells with commas, doubled quotes and line breaks", () => {
    expect(parseCsv('Tên,Ghi chú\n"Hà Nội, VN","nói ""xin chào""\nlần 2"\r\n,3\n')).toEqual([
      ["Tên", "Ghi chú"],
      ["Hà Nội, VN", 'nói "xin chào"\nlần 2'],
      ["", "3"],
    ]);
    expect(parseCsv("a,b")).toEqual([["a", "b"]]);
    expect(parseCsv("")).toEqual([]);
  });

  it("keeps only http(s) and mailto links, base64 images and inline styles in docx output", () => {
    const root = document.createElement("div");
    root.innerHTML =
      '<p onclick="x()" style="padding: 4pt">Chào</p><a href="javascript:alert(1)">bad</a>' +
      '<a href="https://memoryos.vn">ok</a><img src="data:image/png;base64,AAAA"><script>alert(1)</script>';
    sanitizeDocxInPlace(root);
    const html = root.innerHTML;
    expect(html).toContain('style="padding: 4pt"');
    expect(html).not.toContain("onclick");
    expect(html).not.toContain("javascript:");
    expect(html).not.toContain("<script");
    expect(html).toContain('href="https://memoryos.vn"');
    expect(html).toContain('src="data:image/png;base64,AAAA"');
  });
});
