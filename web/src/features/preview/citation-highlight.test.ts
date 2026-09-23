import { beforeEach, describe, expect, it } from "vitest";
import { placeCitations } from "./citation-highlight";

function render(html: string) {
  document.body.innerHTML = `<div id="doc">${html}</div>`;
  return document.querySelector("#doc")!;
}

beforeEach(() => {
  document.body.innerHTML = "";
});

describe("placeCitations", () => {
  it("places a passage that the renderer split across two paragraphs", () => {
    const container = render("<p>Doanh thu quý 3 </p><p>đạt 412 tỷ đồng.</p>");
    const [placed] = placeCitations(container, ["Doanh thu quý 3 đạt 412 tỷ đồng."]);
    expect(placed?.confidence).toBe("exact");
    expect(placed?.range?.toString()).toBe("Doanh thu quý 3 đạt 412 tỷ đồng.");
  });

  it("places a passage that stops inside a styled run", () => {
    const container = render("<p>Doanh thu <strong>412 tỷ</strong> đồng, tăng 6,2%.</p>");
    const [placed] = placeCitations(container, ["Doanh thu 412 tỷ"]);
    expect(placed?.confidence).toBe("exact");
    expect(placed?.range?.toString()).toBe("Doanh thu 412 tỷ");
  });

  it("gives an absent passage no range at all", () => {
    const container = render("<p>Doanh thu quý 3 đạt 412 tỷ đồng.</p>");
    const [placed] = placeCitations(container, ["Lợi nhuận sau thuế đạt 87 tỷ đồng."]);
    expect(placed).toEqual({ confidence: "none" });
  });

  it("places each passage of a citation, keeping the caller's order", () => {
    const container = render(
      "<p>Doanh thu quý 3 đạt 412 tỷ đồng.</p><p>Chi phí quản lý tăng 12%.</p>",
    );
    const placed = placeCitations(container, [
      "Chi phí quản lý tăng 12%.",
      "Không có trong tài liệu.",
      "Doanh thu quý 3 đạt 412 tỷ đồng.",
    ]);
    expect(placed.map((entry) => entry.confidence)).toEqual(["exact", "none", "exact"]);
    expect(placed[0]?.range?.toString()).toBe("Chi phí quản lý tăng 12%.");
    expect(placed[2]?.range?.toString()).toBe("Doanh thu quý 3 đạt 412 tỷ đồng.");
  });

  it("reads through nested markup without counting it as text", () => {
    const container = render(
      "<section><p><span>Doanh thu </span><em>quý 3</em> đạt <b>412</b> tỷ đồng.</p></section>",
    );
    const [placed] = placeCitations(container, ["Doanh thu quý 3 đạt 412 tỷ đồng."]);
    expect(placed?.confidence).toBe("exact");
    expect(placed?.range?.toString()).toBe("Doanh thu quý 3 đạt 412 tỷ đồng.");
  });

  it("places nothing in an empty container", () => {
    expect(placeCitations(render(""), ["Doanh thu quý 3."])).toEqual([{ confidence: "none" }]);
  });
});
