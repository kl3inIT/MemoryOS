import { describe, expect, it } from "vitest";

import { sourceMutationError, sourceStatusMessage } from "@/features/sources/source-errors";
import { ApiError } from "@/lib/api";

describe("source error presentation", () => {
  it("preserves safe unknown error references but never presents raw diagnostic text", () => {
    const code = "SOURCE_PROVIDER_RATE_LIMITED";
    const secret = "database password leaked";
    expect(sourceStatusMessage(code)).toContain(code);
    expect(sourceStatusMessage(secret)).not.toContain(secret);
    expect(sourceMutationError(new ApiError(500, { code, detail: secret }), "reindex")).toContain(
      code,
    );
    expect(
      sourceMutationError(new ApiError(500, { code: secret, detail: secret }), "google-drive"),
    ).not.toContain(secret);
    expect(sourceMutationError(new Error(secret), "delete-source")).not.toContain(secret);
  });
});
