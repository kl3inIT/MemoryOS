import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQueryClient } from "@tanstack/react-query";
import { KeyRound } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  createSharePointCredential,
  replaceSharePointCredentialAuthentication,
} from "@/lib/hey-api/sdk.gen";
import type { SharePointCredentialResponse } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { SharePointEntraGuide } from "./sharepoint-entra-guide";
import {
  SharePointCredentialInput,
  type SharePointAuthMethod,
  type SharePointCredentialInputHandle,
} from "./sharepoint-credential-input";

const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type CredentialDialogProps = {
  open: boolean;
  /** Counts openings, so every opening starts from an empty form. */
  session: number;
  /** The credential whose authentication is replaced, or null to register a new one. */
  replacing: SharePointCredentialResponse | null;
  /** Any work of the credential step, the dialog's own included; the dialog stays open while it runs. */
  busy: boolean;
  triggerRef: RefObject<HTMLButtonElement | null>;
  onOpenChange: (open: boolean) => void;
  onBusyChange: (busy: boolean) => void;
  onSaved: (credential: SharePointCredentialResponse) => Promise<void>;
};

/** Registers an Entra application, or replaces its secret or certificate, once Microsoft accepts it. */
export function SharePointCredentialDialog(props: CredentialDialogProps) {
  const ui = useAppTranslation();
  const { open, busy, replacing, triggerRef, onOpenChange } = props;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="sm:max-w-4xl"
        onCloseAutoFocus={(event) => {
          event.preventDefault();
          triggerRef.current?.focus();
        }}
        onEscapeKeyDown={(event) => {
          if (busy) event.preventDefault();
        }}
        onPointerDownOutside={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        <DialogHeader>
          <DialogTitle>
            {replacing
              ? ui("Replace SharePoint authentication")
              : ui("Create a SharePoint credential")}
          </DialogTitle>
          <DialogDescription>
            {ui(
              "The credential is verified with Microsoft before it is stored, so what Entra rejects is never saved.",
            )}
          </DialogDescription>
        </DialogHeader>
        <CredentialForm key={props.session} {...props} />
      </DialogContent>
    </Dialog>
  );
}

function CredentialForm({
  replacing,
  busy: stepBusy,
  onBusyChange,
  onSaved,
}: CredentialDialogProps) {
  const ui = useAppTranslation();
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const credentialInput = useRef<SharePointCredentialInputHandle>(null);
  const active = useRef(true);
  const submitting = useRef(false);
  const [name, setName] = useState(replacing?.name ?? "");
  const [directoryId, setDirectoryId] = useState(replacing?.directoryId ?? "");
  const [clientId, setClientId] = useState(replacing?.clientId ?? "");
  const [method, setMethod] = useState<SharePointAuthMethod>(
    replacing?.authMethod === "CERTIFICATE" ? "CERTIFICATE" : "CLIENT_SECRET",
  );
  const [authenticationReady, setAuthenticationReady] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);
  const busy = saving || stepBusy;

  useLayoutEffect(() => {
    active.current = true;
    const input = credentialInput.current;
    return () => {
      active.current = false;
      input?.clear();
    };
  }, []);

  useEffect(() => {
    onBusyChange(saving);
    return () => onBusyChange(false);
  }, [saving, onBusyChange]);

  async function save() {
    if (submitting.current || busy || !name.trim() || !authenticationReady) return;
    if (!GUID.test(directoryId.trim()) || !GUID.test(clientId.trim())) {
      setError("Supply the Directory (tenant) ID and Application (client) ID as GUIDs.");
      return;
    }
    // The secret or keystore is read here and never stored in React Query variables or state.
    const authentication = credentialInput.current?.take();
    if (!authentication) {
      setError(
        method === "CLIENT_SECRET"
          ? "Paste the client secret Value before saving."
          : "Choose the PKCS#12 keystore before saving.",
      );
      return;
    }
    submitting.current = true;
    setSaving(true);
    setError(null);
    try {
      const body = {
        name: name.trim(),
        directoryId: directoryId.trim(),
        clientId: clientId.trim(),
        cloud: "GLOBAL" as const,
        ...authentication,
      };
      const { data } = replacing
        ? await replaceSharePointCredentialAuthentication({
            path: { credentialId: replacing.id },
            headers: {
              "If-Match": `"${replacing.credentialRevision}"`,
            },
            body,
          })
        : await createSharePointCredential({
            body,
          });
      if (!active.current) return;
      notify({
        title: replacing ? "Credential updated" : "Credential verified",
        description: replacing
          ? `${data.name} was verified with Microsoft and its authentication replaced.`
          : `${data.name} was verified with Microsoft and is ready to use with a Source.`,
        tone: "success",
      });
      await onSaved(data);
    } catch (cause) {
      if (!active.current) return;
      if (isUnauthenticated(cause))
        void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
      setError(sourceMutationError(cause, "sharepoint-credential"));
    } finally {
      submitting.current = false;
      if (active.current) setSaving(false);
    }
  }

  return (
    <form
      className="grid gap-5"
      onSubmit={(event) => {
        event.preventDefault();
        void save();
      }}
    >
      <div className="grid min-w-0 gap-5 lg:grid-cols-[minmax(16rem,0.8fr)_minmax(0,1.2fr)]">
        <aside className="h-fit rounded-xl border border-border-subtle bg-surface-base p-4">
          <div className="mb-3 flex items-center gap-2">
            <span className="grid size-8 place-items-center text-content-muted">
              <KeyRound className="size-4" aria-hidden="true" />
            </span>
            <div>
              <h3 className="font-main-ui-action text-content-primary">
                {ui("Microsoft Entra prerequisite")}
              </h3>
              <p className="font-secondary-body text-content-muted">
                {ui("Create and consent the application before entering its identifiers.")}
              </p>
            </div>
          </div>
          <SharePointEntraGuide />
        </aside>
        <div className="min-w-0 space-y-5">
          <div>
            <label
              htmlFor="sharepoint-credential-name"
              className="font-secondary-action text-content-primary"
            >
              {ui("Credential name")}
            </label>
            <Input
              id="sharepoint-credential-name"
              value={name}
              maxLength={120}
              required
              disabled={busy}
              onChange={(event) => setName(event.target.value)}
              placeholder={ui("e.g. Contoso SharePoint")}
              autoComplete="off"
              className="mt-2"
            />
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label
                htmlFor="sharepoint-directory-id"
                className="font-secondary-action text-content-primary"
              >
                {ui("Directory (tenant) ID")}
              </label>
              <Input
                id="sharepoint-directory-id"
                value={directoryId}
                required
                disabled={busy || Boolean(replacing)}
                readOnly={Boolean(replacing)}
                onChange={(event) => setDirectoryId(event.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
                autoComplete="off"
                spellCheck={false}
                className="mt-2 font-mono text-xs"
              />
            </div>
            <div>
              <label
                htmlFor="sharepoint-client-id"
                className="font-secondary-action text-content-primary"
              >
                {ui("Application (client) ID")}
              </label>
              <Input
                id="sharepoint-client-id"
                value={clientId}
                required
                disabled={busy || Boolean(replacing)}
                readOnly={Boolean(replacing)}
                onChange={(event) => setClientId(event.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
                autoComplete="off"
                spellCheck={false}
                className="mt-2 font-mono text-xs"
              />
            </div>
          </div>
          {replacing ? (
            <p className="rounded-lg bg-status-warning-surface p-4 text-sm text-status-warning-content">
              {ui("Replacing authentication affects all")} {replacing.sourceCount}{" "}
              {ui(
                "Sources using this credential. The directory and application stay as they are; saved scopes and documents are retained.",
              )}
            </p>
          ) : null}
          <SharePointCredentialInput
            ref={credentialInput}
            method={method}
            disabled={busy}
            onMethodChange={setMethod}
            onReadyChange={setAuthenticationReady}
          />
        </div>
      </div>
      {error ? (
        <p
          role="alert"
          className="rounded-lg bg-status-danger-surface px-4 py-3 text-sm text-status-danger-content"
        >
          {ui(error)}
        </p>
      ) : null}
      <DialogFooter>
        <DialogClose asChild>
          <Button prominence="secondary" disabled={busy}>
            {ui("Cancel")}
          </Button>
        </DialogClose>
        <Button
          type="submit"
          pending={saving}
          disabled={busy || !name.trim() || !authenticationReady}
        >
          {ui("Verify and save")}
        </Button>
      </DialogFooter>
    </form>
  );
}
