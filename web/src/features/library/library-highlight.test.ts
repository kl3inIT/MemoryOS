import { expect, it } from "vitest";
import { highlightParts } from "./library";

it("marks every occurrence of the query, whatever its case", () => {
  expect(highlightParts("Điều khoản THANH TOÁN và thanh toán lại", "thanh toán")).toEqual([
    { text: "Điều khoản ", match: false },
    { text: "THANH TOÁN", match: true },
    { text: " và ", match: false },
    { text: "thanh toán", match: true },
    { text: " lại", match: false },
  ]);
});

it("leaves the passage alone when there is nothing to mark", () => {
  expect(highlightParts("Không có gì", "   ")).toEqual([{ text: "Không có gì", match: false }]);
  expect(highlightParts("Không có gì", "khác")).toEqual([{ text: "Không có gì", match: false }]);
});
