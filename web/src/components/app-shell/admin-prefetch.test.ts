import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { mayWarmAdminPage } from "./admin-prefetch";

function clientWith(identity?: { capabilities: string[]; scopedCapabilities: string[] }) {
  const client = new QueryClient();
  if (identity) client.setQueryData(getCurrentIdentityQueryKey(), identity);
  return client;
}

describe("admin page warming", () => {
  it("warms only pages the signed-in person may open", () => {
    const groupManager = clientWith({ capabilities: [], scopedCapabilities: ["GROUPS_READ"] });
    expect(mayWarmAdminPage(groupManager, "groups")).toBe(true);
    expect(mayWarmAdminPage(groupManager, "users")).toBe(false);
    expect(mayWarmAdminPage(groupManager, "models")).toBe(false);
  });

  it("warms nothing before identity is known", () => {
    expect(mayWarmAdminPage(clientWith(), "sources")).toBe(false);
  });
});
