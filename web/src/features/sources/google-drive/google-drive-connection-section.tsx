import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ChevronDown, KeyRound, TriangleAlert, Unplug } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { StatusBadge } from "@/components/ui/status-badge";
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
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { SourceSectionIcon } from "@/features/sources/shared/source-section-icon";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import {
  GoogleDriveOAuthClientInput,
  type GoogleDriveOAuthClientInputHandle,
} from "./google-drive-oauth-client-input";

type ConnectionAction = "authorize" | "disconnect";

/**
 * The shared credential behind a Drive Source: its state, and reconnecting or disconnecting it
 * for every Source that uses it.
 */
export function GoogleDriveConnectionSection({
  sourceId,
  resourceKey,
  configuration,
  credential,
  credentialsUnavailable,
  credentialRefresh,
  connected,
  canReauthorize,
  canReplaceClient,
  canRevoke,
  controlsDisabled,
  hasSelectionChanges,
  activeAction,
  leaving,
  stale,
  perform,
  onLeavingChange,
}: {
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
}) {
  const ui = useAppTranslation();
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

  return (
    <section aria-labelledby="source-credentials-heading">
      <Card size="sm">
        <CardContent>
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-3">
              <SourceSectionIcon icon={KeyRound} />
              <h2 id="source-credentials-heading" className="font-heading-h3 text-content-primary">
                {ui("Credentials")}
              </h2>
            </div>
            <Collapsible className="group" defaultOpen={Boolean(!connected ? true : undefined)}>
              <CollapsibleTrigger asChild>
                <button
                  type="button"
                  className="flex min-h-11 w-full cursor-pointer flex-wrap items-center gap-3 rounded-lg text-left text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
                >
                  <StatusBadge tone={connected ? "success" : "warning"}>
                    {connected
                      ? ui("Connected")
                      : serviceAccount
                        ? ui("Needs a new key")
                        : ui("Needs reconnect")}
                  </StatusBadge>
                  <span className="min-w-0 break-all text-content-muted">
                    {serviceAccount && credential?.serviceAccountEmail
                      ? ui("Service account {{v1}} acting as {{v2}}", {
                          v1: credential.serviceAccountEmail,
                          v2: configuration.accountEmail,
                        })
                      : configuration.accountEmail}
                  </span>
                  <span className="ml-auto inline-flex items-center gap-2 text-content-muted">
                    <span className="group-data-[state=open]:hidden">
                      {canReauthorize || canRevoke
                        ? ui("Manage connection")
                        : ui("Connection details")}
                    </span>
                    <span className="hidden group-data-[state=open]:inline">{ui("Close")}</span>
                    <ChevronDown
                      className="size-4 group-data-[state=open]:rotate-180 motion-safe:transition-transform"
                      aria-hidden="true"
                    />
                  </span>
                </button>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <div className="mt-4 flex flex-col gap-4">
                  <Alert variant="warning" role="note">
                    <TriangleAlert aria-hidden="true" />
                    <AlertDescription>
                      {ui(
                        "This credential is shared. Reconnecting or disconnecting affects all Sources using it",
                      )}
                      {credential ? ui(" ({{v1}} Sources)", { v1: credential.sourceCount }) : ""}
                      {ui(
                        ", not just this Source. Saved links and indexed documents are retained.",
                      )}
                    </AlertDescription>
                  </Alert>
                  {credentialsUnavailable ? (
                    <Alert variant="destructive">
                      <AlertDescription>
                        {ui("Credential details could not be loaded. Refresh before reconnecting.")}
                      </AlertDescription>
                      <div className="mt-2">
                        <Button
                          size="sm"
                          prominence="secondary"
                          pending={credentialRefresh.pending}
                          onClick={credentialRefresh.refresh}
                        >
                          {ui("Retry credential details")}
                        </Button>
                      </div>
                    </Alert>
                  ) : null}
                  {canReauthorize ? (
                    <div className="flex flex-col gap-3">
                      {!savedClientConfigured ? (
                        <Alert variant="warning" role="note">
                          <TriangleAlert aria-hidden="true" />
                          <AlertDescription>
                            {canReplaceClient
                              ? ui(
                                  "This connection has no saved OAuth app. Upload or paste your Google Web OAuth client JSON below, then reconnect the same Google account. Saved files and folders are retained.",
                                )
                              : ui(
                                  "This connection has no saved OAuth app. Ask a tenant administrator with global Source management permission to add the app and reconnect this credential.",
                                )}
                          </AlertDescription>
                        </Alert>
                      ) : (
                        <>
                          <p className="text-sm text-content-secondary">
                            {ui(
                              "Reconnect reuses the OAuth app saved with this shared credential.",
                            )}
                          </p>
                          {canReplaceClient ? (
                            <Field orientation="horizontal">
                              <Checkbox
                                id="google-drive-replace-client"
                                checked={replaceClient}
                                disabled={controlsDisabled || hasSelectionChanges}
                                onCheckedChange={(event) => {
                                  clientInput.current?.clear();
                                  setClientReady(false);
                                  setReplaceClient(event === true);
                                }}
                              />
                              <FieldLabel htmlFor="google-drive-replace-client">
                                {ui("Replace OAuth app on reconnect")}
                              </FieldLabel>
                            </Field>
                          ) : null}
                        </>
                      )}
                      {needsClient ? (
                        <GoogleDriveOAuthClientInput
                          key={`${resourceKey}:${credentialKey}`}
                          ref={clientInput}
                          disabled={controlsDisabled || hasSelectionChanges}
                          onReadyChange={setClientReady}
                        />
                      ) : null}
                    </div>
                  ) : null}
                  <div className="flex flex-wrap gap-2">
                    {canReauthorize ? (
                      <ConfirmDialog
                        trigger={
                          <Button
                            prominence="secondary"
                            disabled={
                              controlsDisabled ||
                              !credential ||
                              credentialsUnavailable ||
                              hasSelectionChanges ||
                              missingClient ||
                              (needsClient && !clientReady)
                            }
                            pending={activeAction === "authorize" || leaving}
                          >
                            {ui("Reconnect Google Drive")}
                          </Button>
                        }
                        title={ui("Reconnect shared Google credential?")}
                        description={ui(
                          "Reconnecting changes the authorization used by all {{v1}} Sources, including other Sources. Use the same Google account. Saved links and indexed documents are retained.",
                          { v1: credential?.sourceCount ?? ui("attached") },
                        )}
                        confirmLabel={ui("Reconnect")}
                        pendingLabel={ui("Reconnecting")}
                        onConfirm={() => perform("authorize", reconnect)}
                        errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
                      />
                    ) : null}
                    {canRevoke && configuration.credentialStatus !== "REVOKED" ? (
                      <ConfirmDialog
                        trigger={
                          <Button tone="danger" prominence="tertiary" disabled={controlsDisabled}>
                            <Unplug data-icon="inline-start" aria-hidden="true" />
                            {ui("Disconnect")}
                          </Button>
                        }
                        title={ui("Disconnect shared Google credential?")}
                        description={
                          serviceAccount
                            ? ui(
                                "Disconnecting destroys the saved key and stops acquisition for all {{v1}} Sources using this credential, including other Sources. Stored data is not deleted. Replace the key of the same service account to resume.",
                                { v1: credential?.sourceCount ?? ui("attached") },
                              )
                            : ui(
                                "Disconnecting stops acquisition for all {{v1}} Sources using this credential, including other Sources. Stored data is not deleted. Reconnect the same Google account to resume.",
                                { v1: credential?.sourceCount ?? ui("attached") },
                              )
                        }
                        confirmLabel={ui("Disconnect")}
                        pendingLabel={ui("Disconnecting")}
                        onConfirm={() => perform("disconnect", disconnect)}
                        errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
                      />
                    ) : null}
                  </div>
                  <p className="text-xs text-content-muted">
                    {ui(
                      "This credential authorizes importing files. MemoryOS Source groups control who can search and read the imported documents.",
                    )}
                  </p>
                </div>
              </CollapsibleContent>
            </Collapsible>
          </div>
        </CardContent>
      </Card>
    </section>
  );
}
