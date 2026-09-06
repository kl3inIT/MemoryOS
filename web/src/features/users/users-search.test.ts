import { describe, expect, it } from "vitest";
import { usersQuery, usersSearchSchema } from "./users-search";

describe("users URL state", () => {
  it("supplies the server defaults for an unconfigured URL", () => {
    expect(usersSearchSchema.parse({})).toEqual({
      sort: "NAME_ASC",
      page: 0,
      size: 20,
    });
  });

  it("normalizes bounded search, filter, sort, and paging values", () => {
    expect(
      usersSearchSchema.parse({
        search: "  Rowan Brooks  ",
        status: "INACTIVE",
        role: "MEMBER",
        groupId: "36e00a49-923f-4357-8787-318077498a81",
        sort: "EMAIL_DESC",
        page: "2",
        size: "50",
      }),
    ).toEqual({
      search: "Rowan Brooks",
      status: "INACTIVE",
      role: "MEMBER",
      groupId: "36e00a49-923f-4357-8787-318077498a81",
      sort: "EMAIL_DESC",
      page: 2,
      size: 50,
    });
  });

  it("drops unsupported filters and restores safe bounds", () => {
    expect(
      usersSearchSchema.parse({
        search: "x".repeat(201),
        status: "DELETED",
        role: "ADMIN",
        groupId: "not-a-group-id",
        sort: "DROP_TABLE",
        page: "-1",
        size: "37",
      }),
    ).toEqual({
      sort: "NAME_ASC",
      page: 0,
      size: 20,
    });
  });

  it("projects every canonical URL value into the generated server query", () => {
    const search = usersSearchSchema.parse({
      search: "member@example.com",
      status: "INACTIVE",
      role: "MEMBER",
      groupId: "36e00a49-923f-4357-8787-318077498a81",
      sort: "STATUS_DESC",
      page: 3,
      size: 100,
    });

    expect(usersQuery(search)).toEqual(search);
  });
});
