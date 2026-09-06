import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { ApiError, isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { CurrentIdentity } from "@/lib/hey-api/types.gen";

const currentIdentityQueryKey = getCurrentIdentityQueryKey();
const acceptedSessionFingerprints = new WeakMap<QueryClient, string>();
const currentIdentityRefreshes = new WeakMap<QueryClient, Promise<void>>();

export function createMemoryOsQueryClient() {
  let client: QueryClient;
  client = new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        const identityQuery = client
          .getQueryCache()
          .find({ queryKey: currentIdentityQueryKey, exact: true });
        handleAuthorizationFailure(client, error, identityQuery === query);
      },
    }),
    mutationCache: new MutationCache({
      onError: (error) => {
        handleAuthorizationFailure(client, error, false);
      },
    }),
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => {
          if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
            return false;
          }
          return failureCount < 1;
        },
        staleTime: 30_000,
      },
    },
  });
  return client;
}

export function acceptCurrentIdentity(queryClient: QueryClient, identity: CurrentIdentity) {
  const nextFingerprint = JSON.stringify([
    identity.actorId,
    identity.tenant?.role ?? null,
    identity.authorizationVersion,
    [...identity.capabilities].sort(),
    [...identity.scopedCapabilities].sort(),
  ]);
  const acceptedFingerprint = acceptedSessionFingerprints.get(queryClient);
  if (acceptedFingerprint !== undefined && acceptedFingerprint !== nextFingerprint) {
    purgePrivateClientState(queryClient);
  }
  acceptedSessionFingerprints.set(queryClient, nextFingerprint);
}

function handleAuthorizationFailure(
  queryClient: QueryClient,
  error: unknown,
  currentIdentityFailed: boolean,
) {
  if (isUnauthenticated(error)) {
    acceptedSessionFingerprints.delete(queryClient);
    purgePrivateClientState(queryClient);
    if (!currentIdentityFailed) {
      void queryClient.resetQueries({ queryKey: currentIdentityQueryKey, exact: true });
    }
    return;
  }

  if (
    currentIdentityFailed ||
    !(error instanceof ApiError) ||
    error.status !== 403 ||
    currentIdentityRefreshes.has(queryClient)
  ) {
    return;
  }

  const refresh = queryClient
    .invalidateQueries({
      queryKey: currentIdentityQueryKey,
      exact: true,
      refetchType: "active",
    })
    .catch(() => undefined);
  currentIdentityRefreshes.set(queryClient, refresh);
  void refresh.finally(() => {
    if (currentIdentityRefreshes.get(queryClient) === refresh) {
      currentIdentityRefreshes.delete(queryClient);
    }
  });
}

function purgePrivateClientState(queryClient: QueryClient) {
  const queryCache = queryClient.getQueryCache();
  const identityQuery = queryCache.find({ queryKey: currentIdentityQueryKey, exact: true });
  for (const query of queryCache.getAll()) {
    if (query !== identityQuery) {
      query.reset();
      queryCache.remove(query);
    }
  }
  queryClient.getMutationCache().clear();
}

export const queryClient = createMemoryOsQueryClient();
