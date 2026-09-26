import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQueryClient } from "@tanstack/react-query";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { KeyRound, TriangleAlert } from "lucide-react";
import { z } from "zod";
import { useAppForm } from "@/components/form/app-form";
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { FieldGroup } from "@/components/ui/field";
import { isUnauthenticated } from "@/lib/api";
import { getCurrentIdentityQueryKey } from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  createSharePointCredential,
  replaceSharePointCredentialAuthentication,
} from "@/lib/hey-api/sdk.gen";
import type { SharePointCredentialResponse } from "@/lib/hey-api/types.gen";
import { zSharePointCredentialRequest } from "@/lib/hey-api/zod.gen";
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
  const [method, setMethod] = useState<SharePointAuthMethod>(
    replacing?.authMethod === "CERTIFICATE" ? "CERTIFICATE" : "CLIENT_SECRET",
  );
  const [authenticationReady, setAuthenticationReady] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);
  const guid = z
    .string()
    .trim()
    .regex(GUID, ui("Supply the Directory (tenant) ID and Application (client) ID as GUIDs."));
  const form = useAppForm({
    defaultValues: {
      credentialName: replacing?.name ?? "",
      directoryId: replacing?.directoryId ?? "",
      clientId: replacing?.clientId ?? "",
    },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: zSharePointCredentialRequest.pick({ directoryId: true, clientId: true }).extend({
        // The field id is its name, so it is distinct from the credential rename beside it.
        credentialName: zSharePointCredentialRequest.shape.name,
        directoryId: guid,
        clientId: guid,
      }),
    },
    onSubmit: ({ value }) => save(value),
  });
  const saving = useStore(form.store, (state) => state.isSubmitting);
  // Save waits for a name and the secret or keystore, and names what is missing by staying disabled.
  const named = useStore(form.store, (state) => Boolean(state.values.credentialName.trim()));
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

  async function save(value: { credentialName: string; directoryId: string; clientId: string }) {
    if (stepBusy || !value.credentialName.trim() || !authenticationReady) return;
    // A direct call, not a mutation: the secret or keystore is read here and never enters React Query.
    const authentication = credentialInput.current?.take();
    if (!authentication) {
      setError(
        method === "CLIENT_SECRET"
          ? "Paste the client secret Value before saving."
          : "Choose the PKCS#12 keystore before saving.",
      );
      return;
    }
    setError(null);
    try {
      const body = {
        name: value.credentialName.trim(),
        directoryId: value.directoryId.trim(),
        clientId: value.clientId.trim(),
        cloud: "GLOBAL" as const,
        ...authentication,
      };
      const { data } = replacing
        ? await replaceSharePointCredentialAuthentication({
            path: { credentialId: replacing.id },
            headers: { "If-Match": `"${replacing.credentialRevision}"` },
            body,
          })
        : await createSharePointCredential({ body });
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
    }
  }

  return (
    <form
      className="flex flex-col gap-5"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="grid min-w-0 gap-5 lg:grid-cols-5">
        <aside className="h-fit rounded-xl border border-border-subtle bg-surface-base p-4 lg:col-span-2">
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
        <FieldGroup className="min-w-0 lg:col-span-3">
          <form.AppField name="credentialName">
            {(field) => (
              <field.TextField
                label={ui("Credential name")}
                maxLength={120}
                disabled={busy}
                placeholder={ui("e.g. Contoso SharePoint")}
                autoComplete="off"
              />
            )}
          </form.AppField>
          <div className="grid gap-4 sm:grid-cols-2">
            <form.AppField name="directoryId">
              {(field) => (
                <field.TextField
                  label={ui("Directory (tenant) ID")}
                  disabled={busy || Boolean(replacing)}
                  readOnly={Boolean(replacing)}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </form.AppField>
            <form.AppField name="clientId">
              {(field) => (
                <field.TextField
                  label={ui("Application (client) ID")}
                  disabled={busy || Boolean(replacing)}
                  readOnly={Boolean(replacing)}
                  placeholder="00000000-0000-0000-0000-000000000000"
                  autoComplete="off"
                  spellCheck={false}
                />
              )}
            </form.AppField>
          </div>
          {replacing ? (
            <Alert variant="warning" role="note">
              <TriangleAlert aria-hidden="true" />
              <AlertDescription>
                {ui("Replacing authentication affects all")} {replacing.sourceCount}{" "}
                {ui(
                  "Sources using this credential. The directory and application stay as they are; saved scopes and documents are retained.",
                )}
              </AlertDescription>
            </Alert>
          ) : null}
          <SharePointCredentialInput
            ref={credentialInput}
            method={method}
            disabled={busy}
            onMethodChange={setMethod}
            onReadyChange={setAuthenticationReady}
          />
        </FieldGroup>
      </div>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{ui(error)}</AlertDescription>
        </Alert>
      ) : null}
      <DialogFooter>
        <DialogClose asChild>
          <Button prominence="secondary" disabled={busy}>
            {ui("Cancel")}
          </Button>
        </DialogClose>
        <Button type="submit" pending={saving} disabled={busy || !named || !authenticationReady}>
          {ui("Verify and save")}
        </Button>
      </DialogFooter>
    </form>
  );
}
