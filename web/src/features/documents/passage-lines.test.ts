import { expect, it } from "vitest";
import { scanLines } from "./passage-lines";

const quad = [
  [10, 20],
  [110, 20],
  [110, 40],
  [10, 40],
];

it("reads the lines a viewer can place and leaves out the rest", () => {
  const lines = scanLines({
    lines: [
      {
        pageNo: 1,
        text: "Nghỉ phép năm",
        coordOrigin: "TOPLEFT",
        frameWidth: 400,
        frameHeight: 300,
        points: quad,
      },
      { pageNo: 2, text: "Trên trang PDF", coordOrigin: "BOTTOMLEFT", points: quad },
      // A frame without a size, a frame in user space, a corner that is not a number, no text, no page.
      { pageNo: 1, text: "khung", coordOrigin: "TOPLEFT", frameWidth: 400, points: quad },
      {
        pageNo: 1,
        text: "gốc dưới",
        coordOrigin: "BOTTOMLEFT",
        frameWidth: 400,
        frameHeight: 300,
        points: quad,
      },
      {
        pageNo: 1,
        text: "góc hỏng",
        coordOrigin: "TOPLEFT",
        points: [[10, Number.NaN], ...quad.slice(1)],
      },
      { pageNo: 1, text: "  ", coordOrigin: "TOPLEFT", points: quad },
      { pageNo: 0, text: "trang 0", coordOrigin: "TOPLEFT", points: quad },
    ],
  });

  expect(lines).toEqual([
    {
      page: 1,
      text: "Nghỉ phép năm",
      origin: "TOPLEFT",
      frame: { width: 400, height: 300 },
      points: quad,
    },
    { page: 2, text: "Trên trang PDF", origin: "BOTTOMLEFT", points: quad },
  ]);
});
