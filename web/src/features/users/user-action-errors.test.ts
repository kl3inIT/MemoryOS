import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { invitationError, membershipActionError } from "./user-action-errors";

describe("localized user problem descriptors", () => {
  it("uses stable business codes while preserving the common HTTP taxonomy", () => {
    expect(invitationError(new ApiError(409, { code: "INVITATION_CONFLICT" }))).toEqual({
      key: "invitationConflict",
    });
    expect(
      membershipActionError(new ApiError(403, { code: "IAM_CONFIGURED_OWNER_PROTECTED" })),
    ).toEqual({ key: "ownerProtected" });
    expect(membershipActionError(new ApiError(403, { detail: "private" }))).toEqual({
      key: "forbidden",
    });
    expect(invitationError(new ApiError(503, { detail: "private" }))).toEqual({
      key: "unavailable",
    });
  });
  it("uses email field metadata but leaves unknown validation fields in the form summary", () => {
    expect(
      invitationError(
        new ApiError(400, { errors: [{ field: "email", code: "EMAIL", message: "private" }] }),
      ),
    ).toEqual({ key: "email", params: {} });
    expect(
      invitationError(new ApiError(400, { errors: [{ field: "newField", code: "REQUIRED" }] })),
    ).toEqual({ key: "validation" });
  });
});
