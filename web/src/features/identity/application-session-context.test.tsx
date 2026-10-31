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

it.each<[string, ApplicationCapability[], string]>([
  ["sends every authenticated member to their groups", [], "/admin/groups"],
  [
    "prefers a privileged administration page over the member Groups fallback",
    ["CHAT_HISTORY_READ", "AUDIT_READ"],
    "/admin/audit",
  ],
  ["sends a user-only administrator to users", ["USERS_MANAGE"], "/admin/users"],
])("%s", (_, capabilities, adminEntryPath) => {
  expect(adminAccess(capabilities)).toMatchObject({ canAccessAdmin: true, adminEntryPath });
});
