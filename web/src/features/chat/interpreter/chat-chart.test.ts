import { describe, expect, it } from "vitest";
import { barRows, MAX_POINTS, parseChart, pointRows } from "./chat-chart";

const line = {
  type: "line",
  title: "Doanh thu quý 3",
  x_label: "Tháng",
  y_label: "Tỷ đồng (₫)",
  y_unit: "₫",
  elements: [
    {
      label: "Hà Nội",
      points: [
        ["T7", 120],
        ["T8", 150],
      ],
    },
    {
      label: "Đà Nẵng",
      points: [
        ["T7", 80],
        ["T9", 95],
      ],
    },
  ],
};

describe("captured chart data", () => {
  it("accepts the E2B line, bar and pie models and merges series for Recharts", () => {
    const parsed = parseChart(line);
    expect(parsed?.title).toBe("Doanh thu quý 3");
    expect(pointRows(line.elements as never)).toEqual([
      { x: "T7", s0: 120, s1: 80 },
      { x: "T8", s0: 150 },
      { x: "T9", s1: 95 },
    ]);

    const bars = [
      { label: "Miền Bắc", group: "2025", value: 4.1 },
      { label: "Miền Bắc", group: "2026", value: 5.1 },
      { label: "Miền Nam", group: "2026", value: 5.0 },
    ];
    expect(parseChart({ type: "bar", elements: bars })?.charts[0]?.type).toBe("bar");
    expect(barRows(bars)).toEqual({
      rows: [
        { x: "Miền Bắc", s0: 4.1, s1: 5.1 },
        { x: "Miền Nam", s1: 5.0 },
      ],
      groups: ["2025", "2026"],
    });
    expect(
      parseChart({
        type: "pie",
        title: null,
        elements: [{ label: "Hà Nội", angle: 144, radius: 1 }],
      })?.charts,
    ).toHaveLength(1);
  });

  it("draws a superchart only when every subplot is drawable", () => {
    expect(
      parseChart({ type: "superchart", title: "Tổng quan", elements: [line, line] })?.charts,
    ).toHaveLength(2);
    expect(
      parseChart({
        type: "superchart",
        elements: [line, { type: "box_and_whisker", elements: [] }],
      }),
    ).toBeUndefined();
  });

  it("falls back to the PNG for unknown, empty, oversized or malformed data", () => {
    expect(parseChart({ type: "unknown", elements: [] })).toBeUndefined();
    expect(parseChart({ type: "line", elements: [] })).toBeUndefined();
    expect(
      parseChart({ type: "bar", elements: [{ label: "A", group: "g", value: "x" }] }),
    ).toBeUndefined();
    const huge = Array.from({ length: MAX_POINTS + 1 }, (_, index) => [index, index]);
    expect(parseChart({ type: "line", elements: [{ label: "A", points: huge }] })).toBeUndefined();
    expect(parseChart("<script>")).toBeUndefined();
  });
});
