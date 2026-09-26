import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import { getCurrentIdentity } from "@/lib/hey-api/sdk.gen";
import { acceptCurrentIdentity } from "@/lib/query-client";

/**
 * The signed-in person's identity, read before the authenticated routes load and observed by the session boundary.
 * A failure is not retried, not even when the boundary mounts after the route load failed: the boundary shows it.
 * Every read passes through {@link acceptCurrentIdentity}, which purges private queries when the actor or its
 * authority changes, so the generated options are extended rather than spread with their own query function.
 */
export function currentIdentityQueryOptions(queryClient: QueryClient) {
  return queryOptions({
    queryKey: getCurrentIdentityQueryKey(),
    queryFn: async ({ signal }) => {
      const { data } = await getCurrentIdentity({ signal });
      acceptCurrentIdentity(queryClient, data);
      return data;
    },
    refetchOnWindowFocus: "always",
    retry: false,
    retryOnMount: false,
  });
}
