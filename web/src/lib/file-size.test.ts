import { expect, it } from "vitest";
import { fileSize } from "./file-size";

it("states the size in the reader's locale", () => {
  expect(fileSize(900, "en")).toBe("900 B");
  expect(fileSize(12_698, "en")).toBe("12.4 KB");
  expect(fileSize(12_698, "vi")).toBe("12,4 KB");
  expect(fileSize(5 * 1024 * 1024, "en")).toBe("5 MB");
});
