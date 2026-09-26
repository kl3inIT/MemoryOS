import { describe, expect, it } from "vitest";
import { friendlyMediaType } from "./document-source-presentation";

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
});
