import { describe, expect, it } from "vitest";
import { createSearchSnippet } from "./search-presentation";

describe("search presentation", () => {
  it("centers a bounded snippet on the exact phrase and highlights it", () => {
    const snippet = createSearchSnippet(
      `${"Mở đầu tài liệu. ".repeat(30)}năm ngày làm việc${" Nội dung sau. ".repeat(30)}`,
      "policy.md",
      "năm ngày làm việc",
      120,
    );

    expect(snippet.text.startsWith("…")).toBe(true);
    expect(snippet.text.endsWith("…")).toBe(true);
    expect(snippet.text).toContain("năm ngày làm việc");
    expect(snippet.parts.filter((part) => part.highlighted).map((part) => part.text)).toContain(
      "năm ngày làm việc",
    );
  });
  it("handles identifiers, regex characters and Unicode without constructing HTML", () => {
    const snippet = createSearchSnippet(
      "Mã SP-ORION-042 có biểu thức (A+B)* và dữ liệu 😀 an toàn. <script>alert(1)</script>",
      "report.md",
      "(A+B)* SP-ORION-042",
    );

    expect(snippet.parts.some((part) => part.highlighted && part.text === "SP-ORION-042")).toBe(
      true,
    );
    expect(snippet.text).toContain("😀");
    expect(snippet.text).toContain("<script>alert(1)</script>");
  });
  it("does not invent highlights for a semantic-only result", () => {
    const snippet = createSearchSnippet(
      "Quy trình hoàn ứng phải hoàn thành trong vòng năm ngày làm việc.",
      "policy.md",
      "thời hạn nộp chứng từ công tác",
    );

    expect(snippet.parts.every((part) => !part.highlighted)).toBe(true);
  });
  it("returns a useful fallback when a generated-only chunk has no content", () => {
    expect(createSearchSnippet("Title: empty.md", "empty.md", "empty")).toEqual({
      text: "No preview text available.",
      parts: [],
    });
  });
});
