import type { ReactNode } from "react";
import { renderHook } from "@testing-library/react";
import { expect, it } from "vitest";
import {
  ApplicationSessionContext,
  useAdminAccess,
  type ApplicationCapability,
  type ApplicationSession,
} from "./application-session-context";

function adminAccess(capabilities: ApplicationCapability[]) {
  const session = { capabilities, scopedCapabilities: [] } as unknown as ApplicationSession;
  const wrapper = ({ children }: { children: ReactNode }) => (
    <ApplicationSessionContext value={session}>{children}</ApplicationSessionContext>
  );
  return renderHook(() => useAdminAccess(), { wrapper }).result.current;
}

it("sends every authenticated member to their groups", () => {
  expect(adminAccess([])).toMatchObject({
    canAccessAdmin: true,
    adminEntryPath: "/admin/groups",
  });
});

it("prefers a privileged administration page over the member Groups fallback", () => {
  expect(adminAccess(["CHAT_HISTORY_READ", "AUDIT_READ"]).adminEntryPath).toBe("/admin/audit");
});
