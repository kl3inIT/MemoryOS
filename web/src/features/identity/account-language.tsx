import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { currentIdentityQueryOptions } from "./current-identity-query";

/**
 * Reads the visitor's account language for a page outside the session boundary, such as the not-found page.
 * `language` is undefined for a visitor who is not signed in, who is not sent to sign in; `settled` is false
 * while the identity is being read. It imports nothing from the i18n modules, so loading it on demand adds no
 * shared chunk to the initial load.
 */
export function AccountLanguage({
  children,
}: {
  children: (language: string | undefined, settled: boolean) => ReactNode;
}) {
  const queryClient = useQueryClient();
  const identity = useQuery(currentIdentityQueryOptions(queryClient));
  return children(identity.data?.uiLanguage, !identity.isPending);
}
