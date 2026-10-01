import type { ReactNode } from "react";
import { renderHook } from "@testing-library/react";
import { expect, it } from "vitest";
import {
  ApplicationSessionContext,
  useAdminAccess,
  type ApplicationCapability,
  type ApplicationSession,
} from "./application-session-context";

function adminAccess(
  capabilities: ApplicationCapability[],
  scopedCapabilities: ApplicationCapability[] = [],
) {
  const session = { capabilities, scopedCapabilities } as unknown as ApplicationSession;
  const wrapper = ({ children }: { children: ReactNode }) => (
    <ApplicationSessionContext value={session}>{children}</ApplicationSessionContext>
  );
  return renderHook(() => useAdminAccess(), { wrapper }).result.current;
}

it("keeps a Basic employee outside administration", () => {
  expect(adminAccess([]).canAccessAdmin).toBe(false);
});

it("sends a scoped Group Manager to their Groups", () => {
  expect(adminAccess([], ["GROUPS_READ"]).adminEntryPath).toBe("/admin/groups");
});

it("prefers a privileged administration page over the Group Manager fallback", () => {
  expect(adminAccess(["CHAT_HISTORY_READ", "AUDIT_READ"]).adminEntryPath).toBe("/admin/audit");
});
