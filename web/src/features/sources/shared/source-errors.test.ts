import { describe, expect, it } from "vitest";

import { sourceMutationError, sourceStatusMessage } from "./source-errors";
import { ApiError } from "@/lib/api";

describe("source error presentation", () => {
  it("preserves safe unknown error references but never presents raw diagnostic text", () => {
    const code = "SOURCE_PROVIDER_RATE_LIMITED";
    const secret = "database password leaked";
    expect(JSON.stringify(sourceStatusMessage(code))).toContain(code);
    expect(JSON.stringify(sourceStatusMessage(secret))).not.toContain(secret);
    expect(
      JSON.stringify(sourceMutationError(new ApiError(500, { code, detail: secret }), "reindex")),
    ).toContain(code);
    expect(
      sourceMutationError(new ApiError(500, { code: secret, detail: secret }), "google-drive"),
    ).not.toContain(secret);
    expect(sourceMutationError(new Error(secret), "delete-source")).not.toContain(secret);
  });

  it("words the shared sync engine's cancel and abort codes and keeps the legacy one", () => {
    expect(sourceStatusMessage("SOURCE_PAUSED")).toMatch(/^Canceled by pause/);
    expect(sourceStatusMessage("SOURCE_DELETING")).toBe(
      "Canceled because the Source is being deleted.",
    );
    expect(sourceStatusMessage("SOURCE_SYNC_ITEM_FAILURES_EXCEEDED")).toMatch(
      /too many files failed/,
    );
    expect(sourceStatusMessage("SOURCE_GOOGLE_INCOMPLETE")).toMatch(
      /^Some files could not be acquired/,
    );
  });
});
