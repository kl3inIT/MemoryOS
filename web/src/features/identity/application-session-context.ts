import { createContext, useContext } from "react";
import type { CurrentIdentity, CurrentTenant } from "@/lib/hey-api/types.gen";

export type ApplicationCapability = CurrentIdentity["capabilities"][number];
export type ApplicationSession = CurrentIdentity & { tenant: CurrentTenant };

export const ApplicationSessionContext = createContext<ApplicationSession | null>(null);

export function useApplicationSession() {
  const session = useContext(ApplicationSessionContext);
  if (!session) {
    throw new Error("Application session is unavailable outside ApplicationSessionProvider");
  }
  return session;
}

export function useCan(capability: ApplicationCapability) {
  return useApplicationSession().capabilities.includes(capability);
}

/** The single product rule for who may enter administration and where the entry lands. */
export function useAdminAccess() {
  const canManageInvitations = useCan("INVITATIONS_MANAGE");
  const canManageSources = useCan("SOURCES_MANAGE");
  return {
    canManageInvitations,
    canManageSources,
    canAccessAdmin: canManageInvitations || canManageSources,
    adminEntryPath: canManageSources ? "/admin" : "/admin/invitations",
  } as const;
}
