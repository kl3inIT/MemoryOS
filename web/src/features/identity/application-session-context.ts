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

export function useGlobalCapability(capability: ApplicationCapability) {
  return useApplicationSession().capabilities.includes(capability);
}

export function useCapabilityAuthority(capability: ApplicationCapability) {
  const session = useApplicationSession();
  if (session.capabilities.includes(capability)) return "global";
  if (session.scopedCapabilities.includes(capability)) return "scoped";
  return "none";
}

/** The single product rule for who may enter administration and where the entry lands. */
export function useAdminAccess() {
  const canManageUsers = useGlobalCapability("USERS_MANAGE");
  const canReadGroups = useCapabilityAuthority("GROUPS_READ") !== "none";
  const canReadSources = useCapabilityAuthority("SOURCES_READ") !== "none";
  return {
    canManageUsers,
    canReadGroups,
    canReadSources,
    canAccessAdmin: canManageUsers || canReadGroups || canReadSources,
    adminEntryPath: canReadSources ? "/admin" : canReadGroups ? "/admin/groups" : "/admin/users",
  } as const;
}
