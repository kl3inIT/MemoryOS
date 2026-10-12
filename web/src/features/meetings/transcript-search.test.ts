import { expect, it } from "vitest";
import { matches } from "./transcript-search";

it("finds marked words from a query typed without marks, and paints them as written", () => {
  const text = "Chạy đây ạ. Chay tiếp, chạy nữa.";

  const found = matches(text, "chay");

  expect(found.map(({ start, end }) => text.slice(start, end))).toEqual(["Chạy", "Chay", "chạy"]);
  expect(matches("Đóng góp", "dong")).toEqual([{ start: 0, end: 4 }]);
});

it("takes a query typed with marks to mean exactly those words", () => {
  const text = "Chạy đây ạ. Chay tiếp.";

  expect(matches(text, "chạy").map(({ start, end }) => text.slice(start, end))).toEqual(["Chạy"]);
  expect(matches(text, "  ")).toEqual([]);
});
