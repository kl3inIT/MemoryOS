import { vi } from "vitest";

const ROW_HEIGHT = 40;

/**
 * jsdom has no layout, so every box measures zero and a keyboard sort has nowhere to move. This stacks the elements
 * matching `selector` one under another, in document order, which is all dnd-kit needs to find a neighbour.
 */
export function stackRows(selector: string) {
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
    const rows = [...document.querySelectorAll(selector)];
    const row = rows.indexOf(this);
    // Anything that is not a row is as tall as the whole list, so a list keeps its rows inside itself.
    const top = row < 0 ? 0 : row * ROW_HEIGHT;
    const height = row < 0 ? rows.length * ROW_HEIGHT : ROW_HEIGHT;
    return {
      x: 0,
      y: top,
      top,
      left: 0,
      right: 240,
      bottom: top + height,
      width: 240,
      height,
      toJSON: () => ({}),
    };
  });
  // dnd-kit scrolls the window to keep the lifted row in view; jsdom only logs that it cannot.
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
}
