import { createContext, useContext } from "react";
import type { CurrentIdentity, CurrentOrganization } from "@/lib/hey-api/types.gen";

export type ApplicationCapability = CurrentIdentity["capabilities"][number];
export type ApplicationSession = CurrentIdentity & { organization: CurrentOrganization };

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
