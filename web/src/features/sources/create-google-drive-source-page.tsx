import { uiLocale } from "@/i18n/format";
import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { ArrowLeft, ArrowRight, Ellipsis, KeyRound, Plus, TriangleAlert, X } from "lucide-react";
import { useEffect, useEffectEvent, useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { StatusBadge } from "@/components/ui/status-badge";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Checkbox } from "@/components/ui/checkbox";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import { ApiError, isUnauthenticated, sameOriginMutationHeaders } from "@/lib/api";
import {
  createGoogleDriveSourceMutation,
  deleteGoogleDriveCredentialMutation,
  getCurrentIdentityQueryKey,
  getGoogleDriveSelectionPolicyOptions,
  listGoogleDriveCredentialsOptions,
  listSourcesQueryKey,
  revokeGoogleDriveCredentialMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { startGoogleDriveAuthorization } from "@/lib/hey-api/sdk.gen";
import type {
  CreateGoogleDriveSourceData,
  GetGoogleDriveConfigurationResponse,
  GoogleDriveCredentialResponse,
  SourceSummary,
} from "@/lib/hey-api/types.gen";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import { SourceAccessChoice } from "./source-access-choice";
import { GoogleDriveLinks } from "./google-drive-links";
import { googleDriveSelectionError, parseGoogleDriveLinks } from "./google-drive-selection";
import {
  GoogleDriveOAuthClientInput,
  type GoogleDriveOAuthClientInputHandle,
} from "./google-drive-oauth-client-input";
import { sourceMutationError } from "./source-errors";
import { GoogleDriveConnectionAccount } from "./google-drive-connection-account";
import { googleDriveCredentialReady } from "./google-drive-credential";
import { GoogleDriveServiceAccountForm } from "./google-drive-service-account-form";
import { GoogleDriveIcon } from "./google-drive-icon";
import { useGoogleDriveSelectionOperation } from "./google-drive-selection-operation";
import { sourceStatusMessage } from "./source-errors";
import { SourceGroupPicker } from "./source-group-picker";

/** One bordered block per group of settings, as in Vanta's integration setup. */
const sectionCard = "space-y-5 rounded-2xl border border-border-default bg-surface-base p-6";

export function CreateGoogleDriveSourcePage() {
  const session = useApplicationSession();
  return (
    <GoogleDriveSourceSetup
      key={`${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`}
    />
  );
}

function GoogleDriveSourceSetup() {
  const ui = useAppTranslation();

  const { googleDrive, credentialId, step } = useSearch({
    from: "/_authenticated/admin/sources/new/google-drive",
  });
  const navigate = useNavigate({ from: "/admin/sources/new/google-drive" });
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const globalManage = authority === "global";
  const canManage = authority !== "none";
  const [groupIds, setGroupIds] = useState<Set<string>>(() => new Set());
  const [access, setAccess] = useState<SourceSummary["access"]>("SYNC");
  const credentials = useQuery({
    ...listGoogleDriveCredentialsOptions(),
    enabled: canManage,
    retry: false,
  });
  const createSource = useMutation(createGoogleDriveSourceMutation());
  const policy = useQuery({
    ...getGoogleDriveSelectionPolicyOptions(),
    enabled: canManage,
    retry: false,
  });
  const tracking = useGoogleDriveSelectionOperation("create");
  const [completedOperation, setCompletedOperation] = useState<string | null>(null);
  const revoke = useMutation(revokeGoogleDriveCredentialMutation());
  const remove = useMutation(deleteGoogleDriveCredentialMutation());
  const selected = credentials.data?.find((credential) => credential.id === credentialId);
  const connected = googleDriveCredentialReady(selected);
  const unavailable = !canManage || credentials.isPending || credentials.isError;
  const clientInput = useRef<GoogleDriveOAuthClientInputHandle>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [reconnecting, setReconnecting] = useState<GoogleDriveCredentialResponse | null>(null);
  const [method, setMethod] = useState<GoogleDriveCredentialResponse["authMethod"]>("OAUTH");
  const [replacingKey, setReplacingKey] = useState<GoogleDriveCredentialResponse | null>(null);
  const [savingServiceAccount, setSavingServiceAccount] = useState(false);
  const [managedCredentialId, setManagedCredentialId] = useState<string | null>(null);
  const [replaceClient, setReplaceClient] = useState(false);
  const [clientReady, setClientReady] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [name, setName] = useState("");
  const [sourceName, setSourceName] = useState("");
  const [createdSourceId, setCreatedSourceId] = useState<string | null>(null);
  const [linksText, setLinksText] = useState("");
  const [scopeMode, setScopeMode] =
    useState<GetGoogleDriveConfigurationResponse["scopeMode"]>("SPECIFIC");
  const [error, setError] = useState<AppCopy | null>(null);
  const [leaving, setLeaving] = useState(false);
  const submitting = useRef(false);
  const submittedProposal = useRef<CreateGoogleDriveSourceData["body"] | null>(null);
  const authorizationController = useRef<AbortController | null>(null);
  const busy =
    authorizing ||
    leaving ||
    savingServiceAccount ||
    createSource.isPending ||
    revoke.isPending ||
    remove.isPending;
  const [linksTouched, setLinksTouched] = useState(false);
  const links = scopeMode === "GENERAL" ? [] : parseGoogleDriveLinks(linksText);
  // Readers of a Private Source are its groups; scoped managers also need groups for Auto Sync.
  const showGroups = access === "PRIVATE" || (access === "SYNC" && !globalManage);
  const proposal = {
    name: sourceName.trim(),
    credentialId: selected?.id ?? "",
    scopeMode,
    links,
    groupIds: showGroups && groupIds.size > 0 ? [...groupIds] : undefined,
    access,
    requestId: tracking.requestId ?? "00000000-0000-4000-8000-000000000000",
  };
  const selectionError = googleDriveSelectionError(proposal, policy.data);
  const validSelection = !selectionError;
  const pendingValidation = Boolean(tracking.operation && !tracking.terminal);
  const frozenProposal =
    pendingValidation || tracking.uncertain || tracking.recovering || tracking.recoveryError;
  const modalTrigger = useRef<HTMLButtonElement | null>(null);
  const reportedCallback = useRef<string | null>(null);
  const active = useRef(true);

  const reconnectingId = reconnecting?.id;
  const reconnectingCredential = credentials.data?.find((entry) => entry.id === reconnectingId);
  const canReconnect =
    !unavailable && (reconnectingCredential?.actions.includes("reauthorize") ?? false);
  const canReplaceClient =
    canReconnect &&
    globalManage &&
    (reconnectingCredential?.actions.includes("replace_oauth_client") ?? false);
  const needsClient =
    !reconnecting ||
    (canReplaceClient && (!reconnectingCredential?.oauthClientConfigured || replaceClient));
  const missingClient =
    Boolean(reconnecting) && !reconnectingCredential?.oauthClientConfigured && !canReplaceClient;
  if (reconnecting && !canReplaceClient && (replaceClient || clientReady)) {
    setReplaceClient(false);
    setClientReady(false);
  }
  if (reconnecting && !canReconnect) {
    setReconnecting(null);
    setModalOpen(false);
    setClientReady(false);
    setReplaceClient(false);
  }

  useLayoutEffect(() => {
    if (!reconnectingId) return;
    const input = clientInput.current;
    return () => {
      input?.clear();
      authorizationController.current?.abort();
    };
  }, [reconnectingId, canReplaceClient, needsClient]);

  useEffect(() => {
    if (!googleDrive) {
      reportedCallback.current = null;
      return;
    }
    const callbackKey = `${session.actorId}:${googleDrive}:${credentialId ?? ""}`;
    if (reportedCallback.current === callbackKey) return;
    if (googleDrive === "authorization-failed") {
      reportedCallback.current = callbackKey;
      notify({
        title: "Credential connection failed",
        description: appText(
          "Google authorization was not completed. You can try connecting again.",
        ),
        tone: "error",
      });
    } else if (
      googleDrive === "connected" &&
      connected &&
      selected &&
      !unavailable &&
      !credentials.isFetching
    ) {
      reportedCallback.current = callbackKey;
      notify({
        title: "Credential connected",
        description: appText("{{v1}} is connected and ready to use with a Source.", {
          v1: selected.name,
        }),
        tone: "success",
      });
    }
  }, [
    googleDrive,
    credentialId,
    session.actorId,
    connected,
    selected,
    unavailable,
    credentials.isFetching,
    notify,
  ]);

  useLayoutEffect(() => {
    active.current = true;
    const restore = () => {
      submitting.current = false;
      setAuthorizing(false);
      setLeaving(false);
    };
    const clear = () => {
      clientInput.current?.clear();
      authorizationController.current?.abort();
      authorizationController.current = null;
    };
    window.addEventListener("pageshow", restore);
    window.addEventListener("pagehide", clear);
    return () => {
      active.current = false;
      clear();
      window.removeEventListener("pageshow", restore);
      window.removeEventListener("pagehide", clear);
    };
  }, []);

  const terminalOperation = tracking.terminal ? tracking.operation : null;
  if (terminalOperation && completedOperation !== terminalOperation.id) {
    setCompletedOperation(terminalOperation.id);
    if (terminalOperation.status === "SUCCEEDED") {
      setCreatedSourceId(tracking.receipt?.sourceId ?? null);
    } else {
      setError(
        terminalOperation.status === "SUPERSEDED"
          ? "This creation proposal was superseded or cancelled. No Source was activated by this proposal."
          : sourceStatusMessage(terminalOperation.errorCode ?? "SOURCE_GOOGLE_SELECTION_FAILED"),
      );
    }
  }
  const handleTerminalOperation = useEffectEvent(() => {
    if (terminalOperation?.status !== "SUCCEEDED") return;
    const targetId = tracking.receipt?.sourceId;
    if (!targetId) return;
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      credentials.refetch(),
    ])
      .then(async () => {
        if (!active.current) return;
        await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: targetId } });
        tracking.forget();
      })
      .catch(() => {
        if (active.current)
          setError(
            "The Source was activated but its page could not be opened. Open the created Source; it will not be created twice.",
          );
      });
  });
  useEffect(() => {
    handleTerminalOperation();
  }, [tracking.operation, tracking.terminal]);

  function changeModal(open: boolean) {
    if (busy) return;
    clientInput.current?.clear();
    setClientReady(false);
    setReplaceClient(false);
    setError(null);
    if (!open) {
      setName("");
      setReconnecting(null);
      setReplacingKey(null);
      setMethod("OAUTH");
    }
    setModalOpen(open);
  }

  function replaceKey(credential: GoogleDriveCredentialResponse) {
    if (!credential.actions.includes("replace_key")) return;
    changeModal(true);
    setMethod("SERVICE_ACCOUNT");
    setReplacingKey(credential);
  }

  function serviceAccountSaved(credential: GoogleDriveCredentialResponse) {
    const replaced = Boolean(replacingKey);
    setModalOpen(false);
    setReplacingKey(null);
    setMethod("OAUTH");
    notify({
      title: replaced ? "Service account key replaced" : "Credential connected",
      description: appText("{{v1}} is connected and ready to use with a Source.", {
        v1: credential.name,
      }),
      tone: "success",
    });
    void Promise.all([
      credentials.refetch(),
      ...(replaced
        ? [
            queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
            queryClient.invalidateQueries({ queryKey: [{ _id: "getGoogleDriveConfiguration" }] }),
          ]
        : []),
    ]);
    if (!replaced) void navigate({ search: { credentialId: credential.id } });
  }

  function serviceAccountFailed(cause: unknown) {
    if (isUnauthenticated(cause))
      void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
    void credentials.refetch();
  }

  function editCredential(credential: GoogleDriveCredentialResponse) {
    if (!credential.actions.includes("reauthorize")) return;
    changeModal(true);
    setReconnecting(credential);
    setName(credential.name);
  }

  async function connect() {
    if (reconnecting && (!canReconnect || missingClient)) return;
    if (submitting.current || busy || unavailable || !name.trim() || (needsClient && !clientReady))
      return;
    submitting.current = true;
    const controller = new AbortController();
    authorizationController.current = controller;
    setError(null);
    setAuthorizing(true);
    try {
      // OAuth client JSON must never enter React Query variables or caches.
      const { data: response } = await startGoogleDriveAuthorization({
        headers: sameOriginMutationHeaders,
        body: {
          name: name.trim(),
          ...(needsClient ? { oauthClientJson: clientInput.current?.takeJson() } : {}),
          ...(reconnecting
            ? {
                credentialId: reconnecting.id,
                expectedCredentialRevision: reconnecting.credentialRevision,
              }
            : {}),
        },
        signal: controller.signal,
        throwOnError: true,
      });
      controller.signal.throwIfAborted();
      clientInput.current?.clear();
      setLeaving(true);
      launchGoogleDriveAuthorization(response.authorizationUrl);
    } catch (cause) {
      if (authorizationController.current !== controller || controller.signal.aborted) return;
      submitting.current = false;
      setLeaving(false);
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      setError(sourceMutationError(cause, "google-drive"));
      void credentials.refetch();
    } finally {
      if (authorizationController.current === controller) {
        authorizationController.current = null;
        setAuthorizing(false);
      }
    }
  }

  async function changeCredential(
    credential: GoogleDriveCredentialResponse,
    action: "revoke" | "delete",
  ) {
    if (
      submitting.current ||
      busy ||
      unavailable ||
      !credentials.data?.some(
        (entry) => entry.id === credential.id && entry.actions.includes(action),
      ) ||
      (action === "delete" && credential.sourceCount !== 0)
    )
      throw new Error("Credential is unavailable");
    submitting.current = true;
    try {
      if (action === "revoke") {
        await revoke.mutateAsync({
          path: { credentialId: credential.id },
          headers: sameOriginMutationHeaders,
          body: { expectedCredentialRevision: credential.credentialRevision },
        });
      } else {
        await remove.mutateAsync({
          path: { credentialId: credential.id },
          headers: {
            ...sameOriginMutationHeaders,
            "If-Match": `"${credential.credentialRevision}"`,
          },
        });
      }
      if (!active.current) return;
      notify({
        title: action === "revoke" ? "Credential revoked" : "Credential deleted",
        description:
          action === "revoke"
            ? `${credential.name} was revoked. Synchronization is stopped for Sources using it; saved content is retained.`
            : `${credential.name} was deleted.`,
        tone: "success",
      });
    } finally {
      submitting.current = false;
      await Promise.all([
        credentials.refetch(),
        queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
        ...(action === "revoke"
          ? [
              queryClient.invalidateQueries({ queryKey: [{ _id: "getGoogleDriveConfiguration" }] }),
              queryClient.invalidateQueries({ queryKey: [{ _id: "getSource" }] }),
            ]
          : []),
      ]);
    }
  }

  async function create() {
    if (
      submitting.current ||
      busy ||
      pendingValidation ||
      tracking.recovering ||
      tracking.recoveryError
    )
      return;
    if (createdSourceId) {
      await navigate({ to: "/admin/sources/$sourceId", params: { sourceId: createdSourceId } });
      tracking.forget();
      return;
    }
    if (
      !tracking.uncertain &&
      (unavailable || !connected || !selected || !sourceName.trim() || !validSelection)
    )
      return;
    submitting.current = true;
    setError(null);
    try {
      const requestId = tracking.begin(tracking.terminal);
      const body =
        tracking.uncertain && submittedProposal.current
          ? submittedProposal.current
          : { ...proposal, requestId };
      submittedProposal.current = body;
      const receipt = await createSource.mutateAsync({
        headers: sameOriginMutationHeaders,
        body,
      });
      if (!active.current) return;
      tracking.accept(receipt);
    } catch (cause) {
      if (!active.current) return;
      if (cause instanceof ApiError && cause.status && cause.status >= 400 && cause.status < 500)
        tracking.forget();
      setError(sourceMutationError(cause, "google-drive"));
    } finally {
      submitting.current = false;
    }
  }

  return (
    <SettingsLayout className="max-w-3xl">
      <PageHeader icon={<GoogleDriveIcon />} title={ui("Google Drive")} />
      {googleDrive === "authorization-failed" ? (
        <p
          role="alert"
          className="rounded-lg bg-status-warning-surface px-4 py-3 text-sm text-status-warning-content"
        >
          {ui("Google authorization was not completed. You can try connecting again.")}
        </p>
      ) : googleDrive === "connected" && connected && !unavailable && step !== "connector" ? (
        <p role="status" className="text-sm text-content-secondary">
          {ui("Authorization completed. Select the credential and continue to create a Source.")}
        </p>
      ) : null}
      {!canManage ? (
        <p role="alert">{ui("You do not have permission to manage credentials and Sources.")}</p>
      ) : null}
      {error && !modalOpen ? (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      {tracking.operation && !createdSourceId ? (
        <div
          role="status"
          className="space-y-2 rounded-lg border border-border-subtle bg-surface-subtle p-4 text-sm"
        >
          <StatusBadge tone={pendingValidation ? "info" : "warning"}>
            {pendingValidation ? ui("Pending validation") : ui("Proposal not activated")}
          </StatusBadge>
          <p>
            {pendingValidation
              ? ui(
                  "Google access and roots are being verified. Your Source is not active yet. Leaving this page does not cancel validation; return here to recover its status.",
                )
              : ui("Review the error and edit the proposal before submitting again.")}
          </p>
          <p className="break-all text-xs text-content-muted">
            {ui("Operation")} {tracking.operation.id} · {ui(statusLabel(tracking.operation.status))}
          </p>
        </div>
      ) : null}
      {tracking.recovering ? <p role="status">{ui("Recovering your submitted Source…")}</p> : null}
      {!error && (tracking.recoveryError || tracking.statusUnavailable) ? (
        <p role="alert" className="text-sm text-status-danger-content">
          {ui("Validation status is unavailable. This does not mean creation failed.")}
        </p>
      ) : null}
      {tracking.recoveryError ? (
        <Button prominence="secondary" onClick={() => void tracking.retryRecovery()}>
          {ui("Recover submitted Source")}
        </Button>
      ) : null}
      {tracking.recoveryMissing ? (
        <Button
          prominence="secondary"
          onClick={() => {
            tracking.forget();
            setError(null);
          }}
        >
          {ui("Discard unaccepted request and start again")}
        </Button>
      ) : null}
      {tracking.statusUnavailable ? (
        <Button prominence="secondary" onClick={() => void tracking.retryStatus()}>
          {ui("Retry validation status")}
        </Button>
      ) : null}
      {tracking.uncertain && !busy ? (
        <p className="text-sm text-content-muted">
          {ui(
            "No receipt was received. Retry this unchanged proposal with the same request ID to avoid duplicate Sources.",
          )}
        </p>
      ) : null}
      {policy.isError ? (
        <div className="space-y-2">
          {!error && !tracking.recoveryError && !tracking.statusUnavailable ? (
            <p role="alert" className="text-sm text-status-danger-content">
              {ui("Selection limits could not be loaded. Creation is disabled.")}
            </p>
          ) : null}
          <Button prominence="secondary" onClick={() => void policy.refetch()}>
            {ui("Retry selection policy")}
          </Button>
        </div>
      ) : null}
      {step === "connector" ? (
        <form
          className="space-y-4"
          onSubmit={(event) => {
            event.preventDefault();
            void create();
          }}
        >
          <section aria-labelledby="google-drive-connection-heading" className={sectionCard}>
            <h2
              id="google-drive-connection-heading"
              className="font-heading-h3 text-content-primary"
            >
              {ui("Connection")}
            </h2>
            <GoogleDriveConnectionAccount credential={selected} connected={connected} />
            {unavailable || !connected ? (
              <p role="alert" className="text-sm text-status-warning-content">
                {ui(
                  "Select a connected credential before creating a Source. Return to credentials to refresh or reconnect.",
                )}
              </p>
            ) : null}
          </section>
          <section aria-labelledby="google-drive-settings-heading" className={sectionCard}>
            <h2 id="google-drive-settings-heading" className="font-heading-h3 text-content-primary">
              {ui("Source settings")}
            </h2>
            <div>
              <label
                htmlFor="google-drive-source-name"
                className="text-sm font-medium text-content-primary"
              >
                {ui("Source name")}
              </label>
              <Input
                id="google-drive-source-name"
                value={sourceName}
                maxLength={120}
                required
                disabled={busy || unavailable || frozenProposal || Boolean(createdSourceId)}
                onChange={(event) => {
                  if (tracking.terminal) tracking.forget();
                  setSourceName(event.target.value);
                  setError(null);
                }}
                placeholder={ui("e.g. Team documentation")}
                autoComplete="off"
                className="mt-2"
              />
            </div>
            <div className="space-y-2">
              <span
                id="google-drive-source-access-label"
                className="text-sm font-medium text-content-primary"
              >
                {ui("Visibility")}
              </span>
              <SourceAccessChoice
                id="google-drive-source-access"
                labelledBy="google-drive-source-access-label"
                modes={globalManage ? ["SYNC", "PRIVATE", "PUBLIC"] : ["SYNC", "PRIVATE"]}
                value={access}
                disabled={busy || unavailable || frozenProposal || Boolean(createdSourceId)}
                onValueChange={(next) => {
                  if (tracking.terminal) tracking.forget();
                  setAccess(next);
                  setError(null);
                }}
              />
            </div>
            {showGroups ? (
              <SourceGroupPicker
                label={ui("Access groups")}
                placeholder={
                  globalManage ? ui("Select groups") : ui("Select at least one group you manage.")
                }
                selected={groupIds}
                disabled={busy || unavailable || frozenProposal || Boolean(createdSourceId)}
                onChange={(ids) => {
                  if (tracking.terminal) tracking.forget();
                  setGroupIds(ids);
                  setError(null);
                }}
              />
            ) : null}
          </section>
          <section aria-labelledby="google-drive-content-heading" className={sectionCard}>
            <h2 id="google-drive-content-heading" className="font-heading-h3 text-content-primary">
              {ui("Content")}
            </h2>
            <GoogleDriveLinks
              scopeMode={scopeMode}
              policy={policy.data}
              onScopeModeChange={(mode) => {
                if (tracking.terminal) tracking.forget();
                setScopeMode(mode);
                setError(null);
              }}
              errorMessage={
                error || tracking.recoveryError || tracking.statusUnavailable || policy.isError
                  ? ""
                  : selectionError && linksTouched
                    ? ui(selectionError)
                    : null
              }
              value={linksText}
              disabled={
                busy || unavailable || !connected || frozenProposal || Boolean(createdSourceId)
              }
              onChange={(value) => {
                if (tracking.terminal) tracking.forget();
                setLinksTouched(true);
                setLinksText(value);
                setError(null);
              }}
            />
          </section>
          <footer className="flex flex-wrap justify-between gap-3 pt-2">
            <Button
              prominence="secondary"
              disabled={busy || frozenProposal}
              onClick={() => void navigate({ search: { credentialId } })}
            >
              <ArrowLeft /> {ui("Credentials")}
            </Button>
            <Button
              type="submit"
              pending={createSource.isPending}
              disabled={
                busy ||
                pendingValidation ||
                tracking.recovering ||
                tracking.recoveryError ||
                (!createdSourceId &&
                  !tracking.uncertain &&
                  (unavailable || !connected || !sourceName.trim() || !validSelection))
              }
            >
              {createdSourceId
                ? ui("Open created Source")
                : tracking.uncertain
                  ? ui("Retry Create Source")
                  : ui("Create Source")}{" "}
              <ArrowRight />
            </Button>
          </footer>
        </form>
      ) : (
        <section aria-labelledby="credential-heading" className={sectionCard}>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="space-y-1">
              <h2 id="credential-heading" className="font-heading-h3 text-content-primary">
                {ui("Select a credential")}
              </h2>
              <p className="text-sm text-content-muted">{ui("Choose an account.")}</p>
            </div>
            <Button
              prominence="secondary"
              disabled={unavailable || busy || frozenProposal}
              onClick={(event) => {
                modalTrigger.current = event.currentTarget;
                changeModal(true);
              }}
            >
              <Plus /> {ui("Create New")}
            </Button>
          </div>
          <div>
            <TooltipProvider>
              <RadioGroup
                value={credentialId ?? ""}
                onValueChange={(selected) => {
                  setError(null);
                  void navigate({ search: { credentialId: selected } });
                }}
              >
                <Table className="w-full table-fixed text-sm">
                  <TableCaption className="sr-only">{ui("Google Drive credentials")}</TableCaption>
                  <TableHeader className="hidden bg-surface-raised text-xs text-content-secondary sm:table-header-group">
                    <TableRow>
                      <TableHead scope="col" className="w-12 py-3">
                        <span className="sr-only">{ui("Select")}</span>
                      </TableHead>
                      <TableHead scope="col" className="w-[14%] px-2 py-3 text-left font-medium">
                        {ui("ID")}
                      </TableHead>
                      <TableHead scope="col" className="px-2 py-3 text-left font-medium">
                        {ui("Name")}
                      </TableHead>
                      <TableHead scope="col" className="w-[17%] px-2 py-3 text-left font-medium">
                        {ui("Created")}
                      </TableHead>
                      <TableHead scope="col" className="w-[17%] px-2 py-3 text-left font-medium">
                        {ui("Last Updated")}
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  {credentials.data?.map((credential) => {
                    const ready = googleDriveCredentialReady(credential);
                    const serviceAccount = credential.authMethod === "SERVICE_ACCOUNT";
                    return (
                      <TableBody
                        key={credential.id}
                        className="block border-b border-border-subtle sm:table-row-group"
                      >
                        <TableRow
                          className={`grid grid-cols-[2.75rem_minmax(0,1fr)_minmax(0,1fr)] gap-x-2 gap-y-2 py-3 sm:table-row ${
                            credential.id === credentialId && ready ? "bg-surface-subtle" : ""
                          }`}
                        >
                          <TableCell className="order-first py-2 align-middle">
                            <label className="flex size-11 cursor-pointer items-center justify-center has-disabled:cursor-default">
                              <RadioGroupItem
                                value={credential.id}
                                aria-label={ui("Select {{v1}}", { v1: credential.name })}
                                disabled={!ready || unavailable || busy || frozenProposal}
                              />
                            </label>
                          </TableCell>
                          <TableCell className="col-span-3 px-2 py-2 align-middle">
                            <span className="mr-2 text-xs text-content-secondary sm:hidden">
                              {ui("ID")}
                            </span>
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <span tabIndex={0} className="font-mono text-xs">
                                  {credential.id.slice(0, 8)}
                                </span>
                              </TooltipTrigger>
                              <TooltipContent className="font-mono">{credential.id}</TooltipContent>
                            </Tooltip>
                          </TableCell>
                          <TableCell className="order-first col-span-2 px-2 py-2 align-middle">
                            <div className="flex items-center gap-2">
                              <div className="min-w-0 flex-1">
                                <span className="min-w-0 flex-1 text-sm">
                                  <span className="block wrap-anywhere font-medium text-content-primary">
                                    {credential.name}
                                  </span>
                                  {serviceAccount ? (
                                    <span className="block wrap-anywhere text-content-secondary">
                                      {ui("Service account {{v1}} acting as {{v2}}", {
                                        v1: credential.serviceAccountEmail ?? "",
                                        v2: credential.accountEmail,
                                      })}
                                    </span>
                                  ) : credential.accountEmail !== credential.name ? (
                                    <span className="block wrap-anywhere text-content-secondary">
                                      {credential.accountEmail}
                                    </span>
                                  ) : null}
                                  {!ready ? (
                                    <span className="mt-1 block">
                                      <StatusBadge tone="warning">
                                        {credential.status === "REVOKED"
                                          ? ui("Revoked")
                                          : serviceAccount
                                            ? ui("Needs a new key")
                                            : ui("Needs reconnect")}
                                      </StatusBadge>
                                    </span>
                                  ) : null}
                                </span>
                              </div>
                              {credential.actions.length > 0 ? (
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <IconButton
                                      aria-label={ui("Manage {{v1}}", { v1: credential.name })}
                                      aria-expanded={managedCredentialId === credential.id}
                                      aria-controls={`credential-actions-${credential.id}`}
                                      className="size-11"
                                      onClick={() =>
                                        setManagedCredentialId(
                                          managedCredentialId === credential.id
                                            ? null
                                            : credential.id,
                                        )
                                      }
                                    >
                                      <Ellipsis />
                                    </IconButton>
                                  </TooltipTrigger>
                                  <TooltipContent>{ui("Manage credential")}</TooltipContent>
                                </Tooltip>
                              ) : null}
                            </div>
                          </TableCell>
                          <TableCell className="col-start-2 px-2 py-2 align-middle text-xs text-content-secondary">
                            <span className="mb-1 block sm:hidden">{ui("Created")}</span>
                            <time dateTime={credential.createdAt}>
                              {new Date(credential.createdAt).toLocaleDateString(uiLocale())}
                            </time>
                          </TableCell>
                          <TableCell className="px-2 py-2 align-middle text-xs text-content-secondary">
                            <span className="mb-1 block sm:hidden">{ui("Last Updated")}</span>
                            <time dateTime={credential.updatedAt}>
                              {new Date(credential.updatedAt).toLocaleDateString(uiLocale())}
                            </time>
                          </TableCell>
                        </TableRow>
                        {managedCredentialId === credential.id && credential.actions.length > 0 ? (
                          <TableRow
                            id={`credential-actions-${credential.id}`}
                            className="block sm:table-row"
                          >
                            <TableCell colSpan={5} className="block px-2 py-3 sm:table-cell">
                              <p className="mb-2 text-sm text-content-secondary">
                                {ui("Used by")} {credential.sourceCount}{" "}
                                {credential.sourceCount === 1 ? ui("Source") : ui("Sources")}.
                              </p>
                              <div className="flex flex-wrap gap-2">
                                {credential.actions.includes("reauthorize") ? (
                                  <Button
                                    prominence="tertiary"
                                    disabled={unavailable || busy || frozenProposal}
                                    onClick={(event) => {
                                      modalTrigger.current = event.currentTarget;
                                      editCredential(credential);
                                    }}
                                  >
                                    {ui("Reconnect")}
                                  </Button>
                                ) : null}
                                {credential.actions.includes("replace_key") ? (
                                  <Button
                                    prominence="tertiary"
                                    disabled={unavailable || busy || frozenProposal}
                                    onClick={(event) => {
                                      modalTrigger.current = event.currentTarget;
                                      replaceKey(credential);
                                    }}
                                  >
                                    {ui("Replace key")}
                                  </Button>
                                ) : null}
                                {credential.actions.includes("revoke") &&
                                credential.status !== "REVOKED" ? (
                                  <ConfirmDialog
                                    trigger={
                                      <Button
                                        tone="danger"
                                        prominence="tertiary"
                                        disabled={unavailable || busy || frozenProposal}
                                      >
                                        {ui("Revoke")}
                                      </Button>
                                    }
                                    title={ui("Revoke {{v1}}?", { v1: credential.name })}
                                    description={
                                      serviceAccount
                                        ? ui(
                                            "This destroys the saved key and stops synchronization for all {{v1}} Sources using this credential. Saved links and documents are retained. Replace the key of the same service account to resume.",
                                            { v1: credential.sourceCount },
                                          )
                                        : ui(
                                            "This stops synchronization for all {{v1}} Sources using this credential. Saved links and documents are retained. Reconnect the same Google account to resume.",
                                            { v1: credential.sourceCount },
                                          )
                                    }
                                    confirmLabel={ui("Revoke")}
                                    pendingLabel={ui("Revoking")}
                                    onConfirm={() => changeCredential(credential, "revoke")}
                                    errorMessage={(cause) =>
                                      sourceMutationError(cause, "google-drive")
                                    }
                                  />
                                ) : null}
                                {credential.actions.includes("delete") ? (
                                  <ConfirmDialog
                                    trigger={
                                      <Button
                                        tone="danger"
                                        prominence="tertiary"
                                        disabled={
                                          unavailable ||
                                          busy ||
                                          frozenProposal ||
                                          credential.sourceCount !== 0
                                        }
                                      >
                                        {ui("Delete")}
                                      </Button>
                                    }
                                    title={ui("Delete {{v1}}?", { v1: credential.name })}
                                    description={
                                      serviceAccount
                                        ? ui(
                                            "Permanently delete this unused credential and its saved service account key. Credentials attached to any Source cannot be deleted.",
                                          )
                                        : ui(
                                            "Permanently delete this unused credential and its saved OAuth app. You will need to authorize again to use it. Credentials attached to any Source cannot be deleted.",
                                          )
                                    }
                                    confirmLabel={ui("Delete credential")}
                                    pendingLabel={ui("Deleting")}
                                    onConfirm={() => changeCredential(credential, "delete")}
                                    errorMessage={(cause) =>
                                      sourceMutationError(cause, "google-drive")
                                    }
                                  />
                                ) : null}
                                {credential.actions.includes("delete") && credential.sourceCount ? (
                                  <p className="w-full text-xs text-content-muted">
                                    {ui(
                                      "Delete all attached Sources before deleting this credential",
                                    )}
                                  </p>
                                ) : null}
                              </div>
                            </TableCell>
                          </TableRow>
                        ) : null}
                      </TableBody>
                    );
                  })}
                </Table>
              </RadioGroup>
            </TooltipProvider>
            {canManage && credentials.isPending ? (
              <p role="status" className="mt-4 text-sm text-content-secondary">
                {ui("Loading credentials…")}
              </p>
            ) : credentials.isError ? (
              <div className="mt-4 space-y-3">
                <p role="alert" className="text-sm text-status-danger-content">
                  {ui("Credentials could not be loaded. Refresh before making changes.")}
                </p>
                <Button
                  prominence="secondary"
                  pending={credentials.isFetching}
                  onClick={() => void credentials.refetch()}
                >
                  {ui("Try again")}
                </Button>
              </div>
            ) : canManage && !credentials.data?.length ? (
              <p className="mt-4 text-sm text-content-primary">
                {ui("No credentials exist for this connector!")}
              </p>
            ) : null}
            {credentialId && !selected && !unavailable ? (
              <p role="alert" className="mt-4 text-sm text-status-warning-content">
                {ui(
                  "The selected credential is no longer available. Select another credential or create a new one.",
                )}
              </p>
            ) : null}
          </div>
          <footer className="flex justify-end border-t border-border-subtle pt-5">
            <Button
              disabled={unavailable || busy || !connected}
              onClick={() => void navigate({ search: { credentialId, step: "connector" } })}
            >
              {ui("Continue")} <ArrowRight />
            </Button>
          </footer>
        </section>
      )}
      <Dialog open={modalOpen} onOpenChange={changeModal}>
        <DialogContent
          showCloseButton={false}
          aria-describedby="credential-modal-description"
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            modalTrigger.current?.focus();
          }}
          onEscapeKeyDown={(event) => {
            if (busy) event.preventDefault();
          }}
          onPointerDownOutside={(event) => {
            if (busy) event.preventDefault();
          }}
          className="flex max-h-[calc(100dvh-2rem)] w-240 max-w-[calc(100dvw-2rem)] flex-col gap-0 overflow-hidden rounded-2xl border border-border-default bg-surface-base p-0 shadow-2xl ring-0 sm:max-w-[calc(100dvw-2rem)] md:left-[calc(50%+var(--sidebar-width)/2)] md:max-w-[calc(100dvw-var(--sidebar-width)-2rem)]"
        >
          <header className="flex shrink-0 items-center gap-3 px-6 py-4">
            <KeyRound className="size-5 shrink-0 text-content-secondary" aria-hidden="true" />
            {/* tailwind-merge cannot match font-heading-h3 against DialogTitle's defaults, so equal sizes displace them. */}
            <DialogTitle className="min-w-0 flex-1 font-heading-h3 text-lg leading-7 font-semibold">
              {replacingKey
                ? ui("Replace a service account key")
                : reconnecting
                  ? ui("Reconnect a Google Drive credential")
                  : ui("Create a Google Drive credential")}
            </DialogTitle>
            <DialogClose asChild>
              <IconButton
                prominence="tertiary"
                aria-label={ui("Close credential dialog")}
                disabled={busy}
              >
                <X />
              </IconButton>
            </DialogClose>
          </header>
          <div className="min-h-0 space-y-5 overflow-y-auto overscroll-contain px-6 pb-6">
            <div>
              <h2 className="font-heading-h2">{ui("Google Drive Authentication")}</h2>
              <DialogDescription
                id="credential-modal-description"
                className="mt-2 text-sm text-content-secondary"
              >
                {method === "SERVICE_ACCOUNT"
                  ? ui(
                      "Use a service account with domain-wide delegation to index your company's Google Workspace.",
                    )
                  : ui("Authenticate with OAuth to access your Google Drive documents.")}
              </DialogDescription>
            </div>
            {globalManage && !reconnecting && !replacingKey ? (
              <Tabs
                value={method}
                onValueChange={(next) => {
                  if (busy) return;
                  clientInput.current?.clear();
                  setClientReady(false);
                  setError(null);
                  setMethod(next === "SERVICE_ACCOUNT" ? "SERVICE_ACCOUNT" : "OAUTH");
                }}
              >
                <TabsList aria-label={ui("Authentication method")}>
                  <TabsTrigger value="OAUTH" disabled={busy}>
                    {ui("OAuth")}
                  </TabsTrigger>
                  <TabsTrigger value="SERVICE_ACCOUNT" disabled={busy}>
                    {ui("Service account")}
                  </TabsTrigger>
                </TabsList>
              </Tabs>
            ) : null}
            {method === "SERVICE_ACCOUNT" ? (
              <GoogleDriveServiceAccountForm
                key={replacingKey?.id ?? "new"}
                replacing={replacingKey}
                disabled={unavailable || !globalManage}
                onPendingChange={setSavingServiceAccount}
                onSaved={serviceAccountSaved}
                onFailed={serviceAccountFailed}
              />
            ) : (
              <form
                className="space-y-5"
                onSubmit={(event) => {
                  event.preventDefault();
                  void connect();
                }}
              >
                <div>
                  <label
                    htmlFor="google-drive-credential-name"
                    className="text-sm font-medium text-content-primary"
                  >
                    {ui("Credential name")}
                  </label>
                  <Input
                    id="google-drive-credential-name"
                    value={name}
                    maxLength={120}
                    required
                    disabled={busy}
                    readOnly={Boolean(reconnecting)}
                    onChange={(event) => setName(event.target.value)}
                    placeholder={ui("e.g. Team Google account")}
                    autoComplete="off"
                    className="mt-2"
                  />
                </div>
                {reconnecting ? (
                  <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
                    {ui("Reconnecting affects all")} {reconnecting.sourceCount}{" "}
                    {ui(
                      "Sources using this credential, not just one Source. Use the same Google account. Saved links and indexed documents are retained.",
                    )}
                  </p>
                ) : null}
                {reconnectingCredential?.oauthClientConfigured && canReplaceClient ? (
                  <label className="flex items-center gap-2 text-sm">
                    <Checkbox
                      checked={replaceClient}
                      disabled={busy}
                      onCheckedChange={(event) => {
                        clientInput.current?.clear();
                        setClientReady(false);
                        setReplaceClient(event === true);
                      }}
                    />
                    {ui("Replace OAuth app on reconnect")}
                  </label>
                ) : null}
                {needsClient ? (
                  <GoogleDriveOAuthClientInput
                    ref={clientInput}
                    disabled={busy || !canManage}
                    onReadyChange={setClientReady}
                  />
                ) : missingClient ? (
                  <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
                    {ui(
                      "This credential has no saved OAuth app. Ask a tenant administrator with global Source management permission to add the app and reconnect it, or create a new credential with your own OAuth app.",
                    )}
                  </p>
                ) : (
                  <p className="text-sm text-content-secondary">
                    {ui("Reconnect reuses the OAuth app saved with this credential.")}
                  </p>
                )}
                <p className="text-sm text-content-secondary">
                  {ui(
                    "Authorization saves a reusable credential, not a Source. Continue afterward to name a Source and select its file and folder links.",
                  )}
                </p>
                {error ? (
                  <Alert variant="destructive">
                    <TriangleAlert aria-hidden="true" />
                    <AlertDescription>{ui(error)}</AlertDescription>
                  </Alert>
                ) : null}
                {leaving ? (
                  <p role="status" className="text-sm text-content-secondary">
                    {ui("Continuing to Google…")}
                  </p>
                ) : null}
                <Button
                  type="submit"
                  pending={authorizing || leaving}
                  disabled={
                    busy ||
                    unavailable ||
                    missingClient ||
                    !name.trim() ||
                    (needsClient && !clientReady)
                  }
                >
                  {ui("Authenticate")}
                </Button>
              </form>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </SettingsLayout>
  );
}
import { statusLabel } from "@/i18n/status-copy";
