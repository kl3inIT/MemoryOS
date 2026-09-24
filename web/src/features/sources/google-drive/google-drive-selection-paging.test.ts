import { describe, expect, it } from "vitest";
import {
  firstSelectionPage,
  nextSelectionPage,
  previousSelectionPage,
} from "./google-drive-selection-paging";

describe("selection paging", () => {
  it("numbers each page from the items before it and returns to the same cursor", () => {
    const second = nextSelectionPage(firstSelectionPage, "c1", 25);
    const third = nextSelectionPage(second, "c2", 25);
    expect(third).toMatchObject({ cursor: "c2", start: 51 });
    expect(previousSelectionPage(third)).toEqual(second);
    expect(previousSelectionPage(second)).toEqual(firstSelectionPage);
  });

  it("stays on the first page when there is no page before it", () => {
    expect(previousSelectionPage(firstSelectionPage)).toBe(firstSelectionPage);
  });
});
