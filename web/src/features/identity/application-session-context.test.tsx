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
    <ApplicationSessionContext.Provider value={session}>
      {children}
    </ApplicationSessionContext.Provider>
  );
  return renderHook(() => useAdminAccess(), { wrapper }).result.current;
}

it("sends a person who may only read chat history to the chat history page", () => {
  expect(adminAccess(["CHAT_HISTORY_READ"])).toMatchObject({
    canAccessAdmin: true,
    adminEntryPath: "/admin/chat-history",
  });
});

it("keeps the audit page as the entry when audit is readable too", () => {
  expect(adminAccess(["CHAT_HISTORY_READ", "AUDIT_READ"]).adminEntryPath).toBe("/admin/audit");
});
