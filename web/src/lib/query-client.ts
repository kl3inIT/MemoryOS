import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";

const currentIdentityQueryKey = getCurrentIdentityQueryKey();
const acceptedSessionFingerprints = new WeakMap<QueryClient, string>();

export function createMemoryOsQueryClient() {
  let client: QueryClient;
  client = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        const identityQuery = client
          .getQueryCache()
          .find({ queryKey: currentIdentityQueryKey, exact: true });
        handleUnauthenticated(client, error, identityQuery === query);
      },
    }),
    mutationCache: new MutationCache({
      onError: (error) => {
        handleUnauthenticated(client, error, false);
      },
    }),
    defaultOptions: {
      queries: {
        retry: 1,
        staleTime: 30_000,
      },
    },
  });
  return client;
}

export function acceptCurrentIdentity(queryClient: QueryClient, identity: CurrentIdentity) {
  const nextFingerprint = JSON.stringify([
    identity.actorId,
    identity.organization?.role ?? null,
    [...identity.capabilities].sort(),
  ]);
  const acceptedFingerprint = acceptedSessionFingerprints.get(queryClient);
  if (acceptedFingerprint !== undefined && acceptedFingerprint !== nextFingerprint) {
    purgePrivateClientState(queryClient);
  }
  acceptedSessionFingerprints.set(queryClient, nextFingerprint);
}

function handleUnauthenticated(
  queryClient: QueryClient,
  error: unknown,
  currentIdentityFailed: boolean,
) {
  if (!isUnauthenticated(error)) return;
  acceptedSessionFingerprints.delete(queryClient);
  purgePrivateClientState(queryClient);
  if (!currentIdentityFailed) {
    void queryClient.resetQueries({ queryKey: currentIdentityQueryKey, exact: true });
  }
}

function purgePrivateClientState(queryClient: QueryClient) {
  const queryCache = queryClient.getQueryCache();
  const identityQuery = queryCache.find({ queryKey: currentIdentityQueryKey, exact: true });
  for (const query of queryCache.getAll()) {
    if (query !== identityQuery) queryCache.remove(query);
  }
  queryClient.getMutationCache().clear();
}

export const queryClient = createMemoryOsQueryClient();
