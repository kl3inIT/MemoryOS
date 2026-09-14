import { describe, expect, it } from "vitest";

import { can } from "./resource-permissions";

type SourceMap = { edit: boolean; delete: boolean };

describe("can", () => {
  it("returns the server-projected value for a present key", () => {
    const source = { permissions: { edit: true, delete: false } satisfies SourceMap };
    expect(can(source, "edit")).toBe(true);
    expect(can(source, "delete")).toBe(false);
  });

  it("fails closed for a missing resource, map or key", () => {
    expect(can<SourceMap>(null, "edit")).toBe(false);
    expect(can<SourceMap>(undefined, "edit")).toBe(false);
    expect(can<SourceMap>({}, "edit")).toBe(false);
    expect(can<SourceMap>({ permissions: null }, "edit")).toBe(false);
    expect(can({ permissions: { edit: true } as Partial<SourceMap> as SourceMap }, "delete")).toBe(
      false,
    );
  });
});
