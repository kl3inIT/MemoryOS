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

/** What an identity lets a person do in administration; the hook and route loaders read the same rule. */
export function adminAuthorityOf(
  identity: Pick<CurrentIdentity, "capabilities" | "scopedCapabilities">,
) {
  const global = (capability: ApplicationCapability) => identity.capabilities.includes(capability);
  const any = (capability: ApplicationCapability) =>
    global(capability) || identity.scopedCapabilities.includes(capability);
  return {
    canManageUsers: global("USERS_MANAGE"),
    canReadGroups: any("GROUPS_READ"),
    canReadSources: any("SOURCES_READ"),
    canManageModels: global("MODELS_MANAGE"),
    canManageProviders: global("SYSTEM_ADMIN"),
    canManageMcp: global("MCP_MANAGE"),
    canManageAgents: global("AGENTS_MANAGE"),
    canReadAudit: global("AUDIT_READ"),
    canReadChatHistory: global("CHAT_HISTORY_READ"),
  } satisfies AdminAuthority;
}

/** The single product rule for who may enter administration and where the entry lands. */
export function useAdminAccess() {
  const authority: AdminAuthority = adminAuthorityOf(useApplicationSession());
  return {
    ...authority,
    authority,
    canAccessAdmin: adminPages.some((page) => page.visible(authority)),
    adminEntryPath: adminEntryPage(authority).to,
  } as const;
}
