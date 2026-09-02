import { describe, expect, it } from "vitest";

import {
  SourceActionError,
  sourceMutationError,
  sourceStatusMessage,
} from "@/features/sources/source-errors";
import { ApiError } from "@/lib/api";

describe("source error presentation", () => {
  it("maps persisted extraction and cleanup codes to product copy", () => {
    expect(sourceStatusMessage("SOURCE_EXTRACTION_ENCRYPTED")).toBe(
      "Password-protected files cannot be indexed.",
    );
    expect(sourceStatusMessage("SOURCE_CLEANUP_INTERNAL")).toBe(
      "Cleanup failed unexpectedly. Try the removal again.",
    );
  });

  it("keeps safe unknown codes as contextual references and hides unsafe detail", () => {
    expect(sourceStatusMessage("SOURCE_PROVIDER_RATE_LIMITED")).toBe(
      "Source processing failed. Error reference: SOURCE_PROVIDER_RATE_LIMITED.",
    );
    expect(sourceStatusMessage("database password leaked")).toBe(
      "Source processing failed. Try the operation again.",
    );
  });

  it("presents transport failures in mutation context", () => {
    expect(sourceMutationError(new ApiError(404, {}), "remove-item")).toBe(
      "This file is no longer available in the source.",
    );
    expect(sourceMutationError(new ApiError(409, {}), "delete-source")).toBe(
      "This source is already changing. Refresh the source and try again.",
    );
    expect(
      sourceMutationError(new ApiError(500, { code: "SOURCE_PROVIDER_RATE_LIMITED" }), "reindex"),
    ).toBe(
      "The source operation could not be completed. Error reference: SOURCE_PROVIDER_RATE_LIMITED.",
    );
  });

  it("maps cleanup protocol failures without exposing exception messages", () => {
    expect(sourceMutationError(new SourceActionError("cleanup-failed"), "delete-source")).toBe(
      "Source cleanup failed. Try deleting the source again.",
    );
    expect(sourceMutationError(new Error("database password leaked"), "delete-source")).toBe(
      "The source operation could not be completed. Try again.",
    );
  });
});
