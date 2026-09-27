import { appText } from "@/i18n/app-text";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { isUnauthenticated } from "@/lib/api";
import {
  getCurrentIdentityQueryKey,
  getGoogleDriveConfigurationQueryKey,
  getSourceQueryKey,
  listGoogleDriveCredentialsQueryKey,
  listSourcesQueryKey,
  revokeGoogleDriveCredentialMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { startGoogleDriveAuthorization } from "@/lib/hey-api/sdk.gen";
import type {
  GetGoogleDriveConfigurationResponse,
  GoogleDriveCredentialResponse,
} from "@/lib/hey-api/types.gen";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import type { GoogleDriveOAuthClientInputHandle } from "./google-drive-oauth-client-input";

type ConnectionAction = "authorize" | "disconnect";

export type GoogleDriveConnectionProps = {
  sourceId: string;
  /** Changes with the Source and the person's authority; a change drops typed client JSON. */
  resourceKey: string;
  configuration: GetGoogleDriveConfigurationResponse;
  credential: GoogleDriveCredentialResponse | undefined;
  credentialsUnavailable: boolean;
  credentialRefresh: { pending: boolean; refresh: () => void };
  connected: boolean;
  canReauthorize: boolean;
  canReplaceClient: boolean;
  canRevoke: boolean;
  controlsDisabled: boolean;
  hasSelectionChanges: boolean;
  activeAction: string | null;
  /** The browser is on its way to Google. */
  leaving: boolean;
  stale: boolean;
  perform: (
    action: ConnectionAction,
    task: (signal: AbortSignal) => Promise<void>,
  ) => Promise<void>;
  onLeavingChange: (leaving: boolean) => void;
};

/**
 * Reconnecting or disconnecting the shared credential behind a Drive Source. Typed OAuth client
 * JSON and an authorization in flight belong to one Source, credential and authority, and are
 * dropped when any of them changes or the page is left.
 */
export function useGoogleDriveConnection({
  sourceId,
  resourceKey,
  configuration,
  credential,
  credentialsUnavailable,
  canReauthorize,
  canReplaceClient,
  stale,
  onLeavingChange,
}: GoogleDriveConnectionProps) {
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const revoke = useMutation(revokeGoogleDriveCredentialMutation());
  const clientInput = useRef<GoogleDriveOAuthClientInputHandle>(null);
  const authorizationController = useRef<AbortController | null>(null);
  const [clientReady, setClientReady] = useState(false);
  const [replaceClient, setReplaceClient] = useState(false);
  const serviceAccount = configuration.credentialAuthMethod === "SERVICE_ACCOUNT";
  const savedClientConfigured =
    credential?.oauthClientConfigured ?? configuration.oauthClientConfigured;
  const needsClient = canReplaceClient && (!savedClientConfigured || replaceClient);
  const missingClient = !savedClientConfigured && !canReplaceClient;
  const credentialKey = `${configuration.credentialId}:${configuration.credentialRevision}`;
  const configurationKey = getGoogleDriveConfigurationQueryKey({ path: { sourceId } });

  const [previous, setPrevious] = useState({
    resourceKey,
    credentialKey,
    canReauthorize,
    canReplaceClient,
  });
  if (
    previous.resourceKey !== resourceKey ||
    previous.credentialKey !== credentialKey ||
    previous.canReauthorize !== canReauthorize ||
    previous.canReplaceClient !== canReplaceClient
  ) {
    setPrevious({ resourceKey, credentialKey, canReauthorize, canReplaceClient });
    if (
      previous.resourceKey !== resourceKey ||
      previous.credentialKey !== credentialKey ||
      !canReauthorize ||
      !canReplaceClient
    ) {
      setReplaceClient(false);
      setClientReady(false);
    }
  }

  useLayoutEffect(() => {
    if (!canReauthorize || !canReplaceClient) {
      clientInput.current?.clear();
      authorizationController.current?.abort();
    }
  }, [canReauthorize, canReplaceClient]);

  // Client JSON and an authorization in flight belong to one Source, credential and authority.
  useLayoutEffect(() => {
    clientInput.current?.clear();
    authorizationController.current?.abort();
    const clear = () => {
      clientInput.current?.clear();
      authorizationController.current?.abort();
    };
    window.addEventListener("pagehide", clear);
    return () => {
      clear();
      window.removeEventListener("pagehide", clear);
    };
  }, [resourceKey, credentialKey]);

  async function reconnect() {
    if (
      !credential ||
      credentialsUnavailable ||
      stale ||
      !canReauthorize ||
      missingClient ||
      (needsClient && !clientReady)
    )
      return;
    const controller = new AbortController();
    authorizationController.current = controller;
    try {
      // A direct call, not a mutation: client JSON must stay out of React Query variables, data and errors.
      const { data: response } = await startGoogleDriveAuthorization({
        body: {
          name: credential.name,
          credentialId: configuration.credentialId,
          expectedCredentialRevision: configuration.credentialRevision,
          ...(needsClient ? { oauthClientJson: clientInput.current?.takeJson() } : {}),
        },
        signal: controller.signal,
      });
      controller.signal.throwIfAborted();
      onLeavingChange(true);
      launchGoogleDriveAuthorization(response.authorizationUrl);
    } catch (cause) {
      if (controller.signal.aborted) return;
      onLeavingChange(false);
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      throw cause;
    } finally {
      if (authorizationController.current === controller) authorizationController.current = null;
    }
  }

  async function disconnect(signal: AbortSignal) {
    if (stale) throw new Error("Refresh the connection before disconnecting");
    await revoke.mutateAsync({
      path: { credentialId: configuration.credentialId },
      body: { expectedCredentialRevision: configuration.credentialRevision },
      signal,
    });
    signal.throwIfAborted();
    await queryClient.cancelQueries({ queryKey: configurationKey });
    queryClient.setQueryData(
      configurationKey,
      (current: GetGoogleDriveConfigurationResponse | undefined) =>
        current && current.credentialRevision === configuration.credentialRevision
          ? { ...current, credentialStatus: "REVOKED", pendingWork: false }
          : current,
    );
    signal.throwIfAborted();
    notify({
      tone: "success",
      title: "Google credential disconnected",
      description: appText(
        "{{v1}}: synchronization is stopped for every attached Source. Saved links and documents are retained.",
        { v1: credential?.name ?? configuration.accountEmail },
      ),
    });
    // Other Sources on this credential read their state afresh when they are next opened.
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: configurationKey }),
      queryClient.invalidateQueries({ queryKey: getSourceQueryKey({ path: { sourceId } }) }),
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      queryClient.invalidateQueries({ queryKey: listGoogleDriveCredentialsQueryKey() }),
    ]);
  }

  return {
    clientInput,
    clientReady,
    setClientReady,
    replaceClient,
    setReplaceClient,
    serviceAccount,
    savedClientConfigured,
    needsClient,
    missingClient,
    credentialKey,
    reconnect,
    disconnect,
  };
}
