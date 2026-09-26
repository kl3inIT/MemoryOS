import { appText } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { TriangleAlert } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  useApplicationSession,
  useCapabilityAuthority,
} from "@/features/identity/application-session-context";
import {
  createGoogleDriveSourceMutation,
  getGoogleDriveSelectionPolicyOptions,
  getGoogleDriveSelectionRequestOptions,
  listGoogleDriveCredentialsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type {
  CreateGoogleDriveSourceData,
  GoogleDriveCredentialResponse,
} from "@/lib/hey-api/types.gen";
import { SourceCreationStatus } from "@/features/sources/shared/source-creation-status";
import { useSourceCreation } from "@/features/sources/shared/use-source-creation";
import { googleDriveCredentialReady } from "./google-drive-credential";
import { GoogleDriveCredentialDialog } from "./google-drive-credential-dialog";
import { GoogleDriveCredentialStep } from "./google-drive-credential-step";
import { GoogleDriveIcon } from "./google-drive-icon";
import { googleDriveSelectionError, parseGoogleDriveLinks } from "./google-drive-selection";
import {
  emptyGoogleDriveSourceDraft,
  type GoogleDriveSourceDraft,
} from "./google-drive-source-draft";
import { GoogleDriveSourceForm } from "./google-drive-source-form";

export function CreateGoogleDriveSourcePage() {
  const session = useApplicationSession();
  return (
    <GoogleDriveSourceSetup
      key={`${session.actorId}:${session.authorizationVersion}:${session.capabilities.join(",")}:${session.scopedCapabilities.join(",")}`}
    />
  );
}

type CredentialDialogState = {
  open: boolean;
  session: number;
  reconnecting: GoogleDriveCredentialResponse | null;
  replacingKey: GoogleDriveCredentialResponse | null;
};

function GoogleDriveSourceSetup() {
  const ui = useAppTranslation();

  const { googleDrive, credentialId, step } = useSearch({
    from: "/_authenticated/admin/sources/new/google-drive",
  });
  const navigate = useNavigate({ from: "/admin/sources/new/google-drive" });
  const notify = useActionNotifications();
  const session = useApplicationSession();
  const authority = useCapabilityAuthority("SOURCES_MANAGE");
  const globalManage = authority === "global";
  const canManage = authority !== "none";
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
  const creation = useSourceCreation({
    provider: "drive",
    recover: (requestId) => getGoogleDriveSelectionRequestOptions({ path: { requestId } }),
    create: (body: CreateGoogleDriveSourceData["body"]) => createSource.mutateAsync({ body }),
    failureCode: "SOURCE_GOOGLE_SELECTION_FAILED",
    errorKind: "google-drive",
    refresh: () => credentials.refetch(),
  });
  const { tracking, error, setError, frozen } = creation;
  const selected = credentials.data?.find((credential) => credential.id === credentialId);
  const connected = googleDriveCredentialReady(selected);
  const unavailable = !canManage || credentials.isPending || credentials.isError;
  const [dialog, setDialog] = useState<CredentialDialogState>({
    open: false,
    session: 0,
    reconnecting: null,
    replacingKey: null,
  });
  const [dialogBusy, setDialogBusy] = useState(false);
  const [credentialsBusy, setCredentialsBusy] = useState(false);
  const [draft, setDraft] = useState<GoogleDriveSourceDraft>(emptyGoogleDriveSourceDraft);
  const modalTrigger = useRef<HTMLButtonElement | null>(null);
  const reportedCallback = useRef<string | null>(null);
  const busy = dialogBusy || credentialsBusy || createSource.isPending;
  const proposal = {
    name: draft.sourceName.trim(),
    credentialId: selected?.id ?? "",
    scopeMode: draft.scopeMode,
    links: draft.scopeMode === "GENERAL" ? [] : parseGoogleDriveLinks(draft.linksText),
    groupIds:
      draft.access === "PRIVATE" && draft.groupIds.size > 0 ? [...draft.groupIds] : undefined,
    access: draft.access,
  };
  const selectionError = googleDriveSelectionError(proposal, policy.data);

  // A reconnect the credential no longer allows closes its dialog.
  const reconnectingCredential = credentials.data?.find(
    (entry) => entry.id === dialog.reconnecting?.id,
  );
  if (
    dialog.reconnecting &&
    (unavailable || !(reconnectingCredential?.actions.includes("reauthorize") ?? false))
  ) {
    setDialog({ ...dialog, open: false, reconnecting: null });
  }

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

  function openDialog(
    trigger: HTMLButtonElement | null,
    target: Pick<CredentialDialogState, "reconnecting" | "replacingKey">,
  ) {
    if (busy) return;
    modalTrigger.current = trigger;
    setError(null);
    setDialog((current) => ({ open: true, session: current.session + 1, ...target }));
  }

  // The dialog has no trigger of its own, so it only ever asks to close.
  function changeDialog(open: boolean) {
    if (busy || open) return;
    setError(null);
    closeDialog();
  }

  function closeDialog() {
    setDialog((current) => ({ ...current, open: false, reconnecting: null, replacingKey: null }));
  }

  function create() {
    if (busy) return;
    void creation.submit(
      proposal,
      !unavailable && connected && Boolean(selected) && Boolean(proposal.name) && !selectionError,
    );
  }

  return (
    <SettingsLayout wide>
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
      {error && !dialog.open ? (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      <SourceCreationStatus
        creation={creation}
        busy={busy}
        pendingMessage={ui(
          "Google access and roots are being verified. Your Source is not active yet. Leaving this page does not cancel validation; return here to recover its status.",
        )}
      />
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
        <GoogleDriveSourceForm
          draft={draft}
          onChange={(change) => creation.edit(() => setDraft({ ...draft, ...change }))}
          selected={selected}
          connected={connected}
          unavailable={unavailable}
          busy={busy}
          creating={createSource.isPending}
          globalManage={globalManage}
          policy={policy.data}
          policyError={policy.isError}
          selectionError={selectionError}
          creation={creation}
          onBack={() => void navigate({ search: { credentialId } })}
          onSubmit={create}
        />
      ) : (
        <GoogleDriveCredentialStep
          credentials={credentials}
          credentialId={credentialId}
          canManage={canManage}
          unavailable={unavailable}
          connected={connected}
          busy={dialogBusy || createSource.isPending}
          frozen={frozen}
          onSelect={(value) => {
            setError(null);
            void navigate({ search: { credentialId: value } });
          }}
          onCreate={(trigger) => openDialog(trigger, { reconnecting: null, replacingKey: null })}
          onReconnect={(trigger, credential) => {
            if (credential.actions.includes("reauthorize"))
              openDialog(trigger, { reconnecting: credential, replacingKey: null });
          }}
          onReplaceKey={(trigger, credential) => {
            if (credential.actions.includes("replace_key"))
              openDialog(trigger, { reconnecting: null, replacingKey: credential });
          }}
          onContinue={() => void navigate({ search: { credentialId, step: "connector" } })}
          onBusyChange={setCredentialsBusy}
        />
      )}
      <GoogleDriveCredentialDialog
        open={dialog.open}
        session={dialog.session}
        reconnecting={dialog.reconnecting}
        replacingKey={dialog.replacingKey}
        credentials={credentials.data}
        unavailable={unavailable}
        canManage={canManage}
        globalManage={globalManage}
        busy={busy}
        triggerRef={modalTrigger}
        onOpenChange={changeDialog}
        onBusyChange={setDialogBusy}
        onSaved={closeDialog}
        refetchCredentials={() => credentials.refetch()}
      />
    </SettingsLayout>
  );
}
