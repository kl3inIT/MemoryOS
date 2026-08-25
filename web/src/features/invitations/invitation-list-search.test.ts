import { describe, expect, it } from "vitest";
import {
  invitationListQuery,
  invitationListSearchSchema,
} from "@/features/invitations/invitation-list-search";

describe("invitation list search", () => {
  it("supplies deterministic defaults for an unconfigured URL", () => {
    expect(invitationListSearchSchema.parse({})).toEqual({
      sort: "CREATED_AT_DESC",
      page: 0,
      size: 20,
    });
  });

  it("normalizes valid filters and numeric URL values", () => {
    expect(
      invitationListSearchSchema.parse({
        status: "PENDING",
        email: "  Member@Example.COM ",
        sort: "EMAIL_ASC",
        page: "2",
        size: "50",
      }),
    ).toEqual({
      status: "PENDING",
      email: "member@example.com",
      sort: "EMAIL_ASC",
      page: 2,
      size: 50,
    });
  });

  it("falls back from unsupported filter, sort, page, and page-size values", () => {
    expect(
      invitationListSearchSchema.parse({
        status: "UNKNOWN",
        email: "x".repeat(255),
        sort: "DROP_TABLE",
        page: "-1",
        size: "37",
      }),
    ).toEqual({
      sort: "CREATED_AT_DESC",
      page: 0,
      size: 20,
    });
  });

  it("passes every canonical list value to the generated API query", () => {
    const search = invitationListSearchSchema.parse({
      status: "REVOKED",
      email: "member@example.com",
      sort: "EMAIL_DESC",
      page: 3,
      size: 100,
    });

    expect(invitationListQuery(search)).toEqual(search);
  });
});
