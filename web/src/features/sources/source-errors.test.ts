import { describe, expect, it } from "vitest";

import {
  SourceActionError,
  sourceMutationError,
  sourceStatusMessage,
} from "@/features/sources/source-errors";
import { ApiError } from "@/lib/api";

describe("source error presentation", () => {
  it("distinguishes supported persisted failures while withholding unsafe backend detail", () => {
    expect(sourceStatusMessage("SOURCE_EXTRACTION_ENCRYPTED")).toMatch(/password-protected/i);
    expect(sourceStatusMessage("SOURCE_CLEANUP_INTERNAL")).toMatch(/cleanup/i);
    expect(sourceStatusMessage("SOURCE_PROVIDER_RATE_LIMITED")).toContain(
      "SOURCE_PROVIDER_RATE_LIMITED",
    );
    expect(sourceStatusMessage("database password leaked")).not.toContain("database password");
  });

  it("preserves action context across transport conflicts", () => {
    expect(sourceMutationError(new ApiError(404, {}), "remove-item")).toMatch(/file/i);
    expect(sourceMutationError(new ApiError(409, {}), "delete-source")).toMatch(/source/i);
    expect(sourceMutationError(new ApiError(409, {}), "associations")).toMatch(/association/i);
    expect(
      sourceMutationError(
        new ApiError(503, { code: "OBJECT_UPLOAD_STORAGE_UNAVAILABLE" }),
        "upload",
      ),
    ).toMatch(/object storage/i);
  });

  it("maps cleanup protocol failures and never echoes exception messages", () => {
    expect(sourceMutationError(new SourceActionError("cleanup-failed"), "delete-source")).toMatch(
      /cleanup/i,
    );
    expect(
      sourceMutationError(new Error("database password leaked"), "delete-source"),
    ).not.toContain("database password");
  });
});
