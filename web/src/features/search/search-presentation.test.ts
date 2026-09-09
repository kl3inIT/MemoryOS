import { describe, expect, it } from "vitest";
import {
  createSearchSnippet,
  friendlyMediaType,
  stripGeneratedTitlePrefix,
} from "./search-presentation";

describe("search presentation", () => {
  it("uses friendly labels without exposing unknown raw MIME values", () => {
    expect(friendlyMediaType("application/pdf")).toBe("PDF");
    expect(friendlyMediaType("application/pdf; charset=utf-8")).toBe("PDF");
    expect(
      friendlyMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ).toBe("Word document");
    expect(friendlyMediaType("image/png")).toBe("Image");
    expect(friendlyMediaType("application/x-private-format")).toBe("Document");
  });

  it("removes only the exact generated title prefix", () => {
    expect(stripGeneratedTitlePrefix("Title: policy.md\nSection: Leave\nBody", "policy.md")).toBe(
      "Section: Leave\nBody",
    );
    expect(stripGeneratedTitlePrefix("Title: another.md\nBody", "policy.md")).toBe(
      "Title: another.md\nBody",
    );
    expect(stripGeneratedTitlePrefix("Intro\nTitle: policy.md\nBody", "policy.md")).toBe(
      "Intro\nTitle: policy.md\nBody",
    );
  });

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
