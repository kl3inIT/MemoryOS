import { createContext, useContext } from "react";
import { adminEntryPage, adminPages } from "@/components/app-shell/admin-pages";
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

/** What the session lets a person do in administration; each page's visibility reads it. */
export type AdminAuthority = {
  canManageUsers: boolean;
  canReadGroups: boolean;
  canReadSources: boolean;
  canManageModels: boolean;
  canManageProviders: boolean;
  canManageMcp: boolean;
  canManageAgents: boolean;
  canReadAudit: boolean;
  canReadChatHistory: boolean;
};

/** The single product rule for who may enter administration and where the entry lands. */
export function useAdminAccess() {
  const authority: AdminAuthority = {
    canManageUsers: useGlobalCapability("USERS_MANAGE"),
    canReadGroups: useCapabilityAuthority("GROUPS_READ") !== "none",
    canReadSources: useCapabilityAuthority("SOURCES_READ") !== "none",
    canManageModels: useGlobalCapability("MODELS_MANAGE"),
    canManageProviders: useGlobalCapability("SYSTEM_ADMIN"),
    canManageMcp: useGlobalCapability("MCP_MANAGE"),
    canManageAgents: useGlobalCapability("AGENTS_MANAGE"),
    canReadAudit: useGlobalCapability("AUDIT_READ"),
    canReadChatHistory: useGlobalCapability("CHAT_HISTORY_READ"),
  };
  return {
    ...authority,
    authority,
    canAccessAdmin: adminPages.some((page) => page.visible(authority)),
    adminEntryPath: adminEntryPage(authority).to,
  } as const;
}
