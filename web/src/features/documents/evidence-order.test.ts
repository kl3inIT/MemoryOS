import { describe, expect, it } from "vitest";
import { firstEvidenceView } from "./evidence-order";

describe("firstEvidenceView", () => {
  it("opens the original of a laid-out file, where the layout is the evidence", () => {
    expect(firstEvidenceView("bao-cao.pdf", "application/pdf")).toBe("original");
    expect(
      firstEvidenceView(
        "hop-dong.docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      ),
    ).toBe("original");
    expect(
      firstEvidenceView(
        "doanh-thu.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      ),
    ).toBe("original");
    expect(firstEvidenceView("so-do.png", "image/png")).toBe("original");
  });

  it("opens the passages of a file whose original adds nothing to its text", () => {
    expect(firstEvidenceView("ghi-chu.md", "text/markdown")).toBe("passages");
    expect(firstEvidenceView("ghi-chu.txt", "text/plain")).toBe("passages");
  });

  it("opens the passages when the original cannot be shown at all", () => {
    expect(firstEvidenceView("bao-cao.doc", "application/msword")).toBe("passages");
    expect(firstEvidenceView("khong-ro.bin", "application/octet-stream")).toBe("passages");
  });
});
