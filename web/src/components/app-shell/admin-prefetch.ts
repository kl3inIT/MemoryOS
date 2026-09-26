import type { QueryClient } from "@tanstack/react-query";
import { adminPage, type AdminPage } from "@/components/app-shell/admin-pages";
import { adminAuthorityOf } from "@/features/identity/application-session-context";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";

/** Route loaders warm a page's data only for a person the page admits, so a denied page sends no request. */
export function mayWarmAdminPage(queryClient: QueryClient, id: AdminPage) {
  const identity = queryClient.getQueryData<CurrentIdentity>(getCurrentIdentityQueryKey());
  return identity !== undefined && adminPage(id).visible(adminAuthorityOf(identity));
}
