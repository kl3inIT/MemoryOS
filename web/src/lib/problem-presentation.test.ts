import { describe, expect, it } from "vitest";
import { ApiError } from "./api";
import { presentProblem } from "./problem-presentation";

describe("problem presentation", () => {
  it.each([
    [401, "unauthenticated", "session"],
    [403, "forbidden", "inline"],
    [400, "validation", "inline"],
    [409, "conflict", "inline"],
    [412, "conflict", "inline"],
    [428, "conflict", "inline"],
    [429, "throttled", "inline"],
    [503, "unavailable", "inline"],
    [500, "unexpected", "inline"],
  ] as const)("classifies mutation %s without defaulting to toasts", (status, kind, placement) => {
    const result = presentProblem(
      new ApiError(status, { detail: "SECRET diagnostic", code: "NEW_UNKNOWN_CODE" }),
      "mutation",
    );
    expect(result).toMatchObject({ kind, placement });
    expect(JSON.stringify(result)).not.toContain("SECRET");
  });
  it("preserves background data for transient errors but not authorization failures", () => {
    expect(presentProblem(new ApiError(503, {}), "backgroundRead")).toMatchObject({
      placement: "notification",
      preserveData: true,
      recovery: "retryRead",
    });
    expect(presentProblem(new ApiError(503, {}), "initialLoad")).toMatchObject({
      placement: "page",
      preserveData: false,
    });
    expect(presentProblem(new ApiError(403, {}), "backgroundRead")).toMatchObject({
      placement: "page",
      preserveData: false,
    });
    expect(presentProblem(new ApiError(503, {}), "mutation").recovery).toBe("reconcile");
  });
  it("allowlists validation codes and parameters without rejected values or prototype lookup", () => {
    const result = presentProblem(
      new ApiError(400, {
        errors: [
          { field: "name", code: "SIZE", params: { min: 1, max: 80, rejectedValue: "secret" } },
          { field: "email", code: "EMAIL", message: "secret" },
          { field: "other", code: "constructor", params: { min: "secret" } },
        ],
      }),
      "mutation",
    );
    expect(result.fields).toEqual({
      name: { key: "size", params: { min: 1, max: 80 } },
      email: { key: "email", params: {} },
      other: { key: "invalid", params: {} },
    });
    expect(JSON.stringify(result)).not.toContain("secret");
  });
});
