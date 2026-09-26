import { describe, expect, it } from "vitest";
import { passageBody, passageSection, stripGeneratedTitlePrefix } from "./passage";

describe("passage", () => {
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
  it("drops the chunk header, which the original document never contains", () => {
    expect(passageBody("Title: policy.md\nSection: Leave > Paid\nDoanh thu 412")).toBe(
      "Doanh thu 412",
    );
    expect(passageBody("Title: policy.md\nDoanh thu 412")).toBe("Doanh thu 412");
    // The chunker truncates a long header on a code point boundary, and the cut line is still the header.
    expect(passageBody("Title: policy.md\nSection: Rat dai nhung bi c\nDoanh thu 412")).toBe(
      "Doanh thu 412",
    );
    // A passage that carries no header is its own body, including text that merely mentions a section.
    expect(passageBody("Doanh thu 412\nSection: Leave")).toBe("Doanh thu 412\nSection: Leave");
  });
  it("keeps the section breadcrumb as the context a citation is read in", () => {
    expect(passageSection("Title: policy.md\nSection: Leave > Paid\nDoanh thu 412")).toBe(
      "Leave > Paid",
    );
    expect(passageSection("Title: policy.md\nDoanh thu 412")).toBeUndefined();
    expect(passageSection("Doanh thu 412\nSection: Leave")).toBeUndefined();
  });
});
