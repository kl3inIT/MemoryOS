import type { ReactNode } from "react";
import {
  ApplicationSessionContext,
  type ApplicationSession,
} from "@/features/identity/application-session-context";

export function ApplicationSessionProvider({
  session,
  children,
}: {
  session: ApplicationSession;
  children: ReactNode;
}) {
  return <ApplicationSessionContext value={session}>{children}</ApplicationSessionContext>;
}
