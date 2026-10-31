import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";

import { SortableList } from "./sortable-list";

afterEach(cleanup);

function clickPrevented() {
  const click = new MouseEvent("click", { bubbles: true, cancelable: true });
  document.body.dispatchEvent(click);
  return click.defaultPrevented;
}

it("stops swallowing clicks when the list unmounts during a drag", () => {
  const view = render(
    <SortableList items={["a", "b"]} getId={(item) => item} onReorder={() => undefined}>
      {(item, handle) => (
        <div ref={handle.setNodeRef}>
          <button ref={handle.setHandleRef} {...handle.attributes}>
            {item}
          </button>
        </div>
      )}
    </SortableList>,
  );
  const handle = screen.getByRole("button", { name: "a" });
  handle.focus();
  fireEvent.keyDown(handle, { code: "Space", key: " " });
  expect(clickPrevented()).toBe(true);

  view.unmount();

  expect(clickPrevented()).toBe(false);
});
