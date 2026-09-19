import { expect, it } from "vitest";
import { speechText } from "./speech-text";

it("reads the words of an answer without markdown, code, images, link targets or citations", () => {
  const answer = [
    "## Kết quả",
    "",
    "MemoryOS **hỗ trợ** [giọng nói](https://example.com/voice) [1][2].",
    "",
    "```ts",
    "const secret = 1;",
    "```",
    "",
    "- Một",
    "- Hai `voice-mode`",
    "",
    "| Tên | Giá trị |",
    "| --- | --- |",
    "| A | 2 |",
    "",
    "![sơ đồ](diagram.png)",
    "",
    "Năm [2026] vẫn giữ nguyên.",
  ].join("\n");
  expect(speechText(answer)).toBe(
    [
      "Kết quả",
      "MemoryOS hỗ trợ giọng nói.",
      "Một",
      "Hai voice-mode",
      "Tên, Giá trị",
      "A, 2",
      "Năm [2026] vẫn giữ nguyên.",
    ].join("\n"),
  );
});

it("returns nothing to read for an answer made only of code", () => {
  expect(speechText("```sql\nSELECT 1;\n```")).toBe("");
});
