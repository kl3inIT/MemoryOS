import { vi } from "vitest";

const ROW_HEIGHT = 40;

/**
 * jsdom has no layout, so every box measures zero and a keyboard sort has nowhere to move. This stacks the elements
 * matching `selector` one under another, in document order, which is all dnd-kit needs to find a neighbour.
 */
export function stackRows(selector: string) {
  vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
    const top = Math.max([...document.querySelectorAll(selector)].indexOf(this), 0) * ROW_HEIGHT;
    return {
      x: 0,
      y: top,
      top,
      left: 0,
      right: 240,
      bottom: top + ROW_HEIGHT,
      width: 240,
      height: ROW_HEIGHT,
      toJSON: () => ({}),
    };
  });
  // dnd-kit scrolls the window to keep the lifted row in view; jsdom only logs that it cannot.
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
}
