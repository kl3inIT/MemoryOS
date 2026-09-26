import { appText } from "@/i18n/app-text";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic, useStore } from "@tanstack/react-form";
import { useQueryClient } from "@tanstack/react-query";
import { useAppForm } from "@/components/form/app-form";
import { useNavigate } from "@tanstack/react-router";
import { KeyRound, TriangleAlert, X } from "lucide-react";
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Field, FieldLabel } from "@/components/ui/field";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { isUnauthenticated } from "@/lib/api";
import {
  getCurrentIdentityQueryKey,
  listSourcesQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { startGoogleDriveAuthorization } from "@/lib/hey-api/sdk.gen";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { zStartGoogleDriveAuthorizationRequest } from "@/lib/hey-api/zod.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { launchGoogleDriveAuthorization } from "./google-drive-authorization";
import {
  GoogleDriveOAuthClientInput,
  type GoogleDriveOAuthClientInputHandle,
} from "./google-drive-oauth-client-input";
import { GoogleDriveServiceAccountForm } from "./google-drive-service-account-form";

type CredentialDialogProps = {
  open: boolean;
  /** Counts openings, so every opening starts from an empty form. */
  session: number;
  /** The credential to reconnect with OAuth, or null. */
  reconnecting: GoogleDriveCredentialResponse | null;
  /** The service-account credential whose key is replaced, or null. */
  replacingKey: GoogleDriveCredentialResponse | null;
  credentials: readonly GoogleDriveCredentialResponse[] | undefined;
  unavailable: boolean;
  canManage: boolean;
  globalManage: boolean;
  /** Any work on the page, the dialog's own included; the dialog stays open while it runs. */
  busy: boolean;
  triggerRef: RefObject<HTMLButtonElement | null>;
  onOpenChange: (open: boolean) => void;
  onBusyChange: (busy: boolean) => void;
  onSaved: () => void;
  refetchCredentials: () => Promise<unknown>;
};

/** Creates or reconnects a Google Drive credential, with OAuth or a service-account key. */
export function GoogleDriveCredentialDialog(props: CredentialDialogProps) {
  const ui = useAppTranslation();
  const { open, busy, reconnecting, replacingKey, triggerRef, onOpenChange } = props;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        showCloseButton={false}
        aria-describedby="credential-modal-description"
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
        // Centred on the content beside the sidebar rather than on the whole window.
        className="w-240 sm:max-w-[calc(100dvw-2rem)] md:left-[calc(50%+var(--sidebar-width)/2)] md:max-w-[calc(100dvw-var(--sidebar-width)-2rem)]"
      >
        <DialogHeader>
          <div className="flex items-center gap-3">
            <KeyRound className="size-5 shrink-0 text-content-secondary" aria-hidden="true" />
            <DialogTitle className="min-w-0 flex-1">
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
          </div>
        </DialogHeader>
        <CredentialForm key={props.session} {...props} />
      </DialogContent>
    </Dialog>
  );
}

function CredentialForm({
  reconnecting,
  replacingKey,
  credentials,
  unavailable,
  canManage,
  globalManage,
  busy: pageBusy,
  onBusyChange,
  onSaved,
  refetchCredentials,
}: CredentialDialogProps) {
  const ui = useAppTranslation();
  const navigate = useNavigate({ from: "/admin/sources/new/google-drive" });
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const [method, setMethod] = useState<GoogleDriveCredentialResponse["authMethod"]>(
    replacingKey ? "SERVICE_ACCOUNT" : "OAUTH",
  );
  const [savingServiceAccount, setSavingServiceAccount] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const ownBusy = authorizing || savingServiceAccount;
  const busy = ownBusy || pageBusy;

  useEffect(() => {
    onBusyChange(ownBusy);
    return () => onBusyChange(false);
  }, [ownBusy, onBusyChange]);

  function serviceAccountSaved(credential: GoogleDriveCredentialResponse) {
    const replaced = Boolean(replacingKey);
    onSaved();
    notify({
      title: replaced ? "Service account key replaced" : "Credential connected",
      description: appText("{{v1}} is connected and ready to use with a Source.", {
        v1: credential.name,
      }),
      tone: "success",
    });
    // Sources on a replaced key read their configuration afresh when they are next opened.
    void Promise.all([
      refetchCredentials(),
      ...(replaced ? [queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() })] : []),
    ]);
    if (!replaced) void navigate({ search: { credentialId: credential.id } });
  }

  function serviceAccountFailed(cause: unknown) {
    if (isUnauthenticated(cause))
      void queryClient.resetQueries({ queryKey: getCurrentIdentityQueryKey(), exact: true });
    void refetchCredentials();
  }

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <h2 className="font-heading-h2">{ui("Google Drive Authentication")}</h2>
        <DialogDescription id="credential-modal-description">
          {method === "SERVICE_ACCOUNT"
            ? ui("Connect with a service account key.")
            : ui("Authenticate with OAuth to access your Google Drive documents.")}
        </DialogDescription>
      </div>
      {globalManage && !reconnecting && !replacingKey ? (
        <ToggleGroup
          type="single"
          variant="outline"
          aria-label={ui("Authentication method")}
          value={method}
          disabled={busy}
          onValueChange={(next) => {
            // Leaving a method drops what was typed for it, OAuth client JSON included.
            if (next) setMethod(next === "SERVICE_ACCOUNT" ? "SERVICE_ACCOUNT" : "OAUTH");
          }}
        >
          <ToggleGroupItem value="OAUTH">{ui("OAuth")}</ToggleGroupItem>
          <ToggleGroupItem value="SERVICE_ACCOUNT">{ui("Service account")}</ToggleGroupItem>
        </ToggleGroup>
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
        <OAuthCredentialForm
          reconnecting={reconnecting}
          credentials={credentials}
          unavailable={unavailable}
          canManage={canManage}
          globalManage={globalManage}
          busy={busy}
          onAuthorizingChange={setAuthorizing}
          refetchCredentials={refetchCredentials}
        />
      )}
    </div>
  );
}

/** Names the credential and, when needed, its OAuth app, then leaves for Google's consent screen. */
function OAuthCredentialForm({
  reconnecting,
  credentials,
  unavailable,
  canManage,
  globalManage,
  busy,
  onAuthorizingChange,
  refetchCredentials,
}: Pick<
  CredentialDialogProps,
  | "reconnecting"
  | "credentials"
  | "unavailable"
  | "canManage"
  | "globalManage"
  | "busy"
  | "refetchCredentials"
> & {
  /** The authorization request runs, or the browser is on its way to Google. */
  onAuthorizingChange: (authorizing: boolean) => void;
}) {
  const ui = useAppTranslation();
  const queryClient = useQueryClient();
  const clientInput = useRef<GoogleDriveOAuthClientInputHandle>(null);
  const authorizationController = useRef<AbortController | null>(null);
  const submitting = useRef(false);
  const form = useAppForm({
    defaultValues: { name: reconnecting?.name ?? "" },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: zStartGoogleDriveAuthorizationRequest.pick({ name: true }),
    },
    onSubmit: () => connect(),
  });
  // Authenticate waits for a name, as for the OAuth app, so it names what is missing by staying disabled.
  const name = useStore(form.store, (state) => state.values.name);
  const [replaceClient, setReplaceClient] = useState(false);
  const [clientReady, setClientReady] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);

  const reconnectingId = reconnecting?.id;
  const reconnectingCredential = credentials?.find((entry) => entry.id === reconnectingId);
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

  useEffect(() => {
    onAuthorizingChange(authorizing || leaving);
    return () => onAuthorizingChange(false);
  }, [authorizing, leaving, onAuthorizingChange]);

  useLayoutEffect(() => {
    if (!reconnectingId) return;
    const input = clientInput.current;
    return () => {
      input?.clear();
      authorizationController.current?.abort();
    };
  }, [reconnectingId, canReplaceClient, needsClient]);

  // Coming back from Google restores the form; leaving the page drops typed client JSON.
  useLayoutEffect(() => {
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
      clear();
      window.removeEventListener("pageshow", restore);
      window.removeEventListener("pagehide", clear);
    };
  }, []);

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
      // A direct call, not a mutation: OAuth client JSON must never enter React Query variables or caches.
      const { data: response } = await startGoogleDriveAuthorization({
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
      void refetchCredentials();
    } finally {
      if (authorizationController.current === controller) {
        authorizationController.current = null;
        setAuthorizing(false);
      }
    }
  }

  return (
    <form
      className="flex flex-col gap-5"
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <form.AppField name="name">
        {(field) => (
          <field.TextField
            label={ui("Credential name")}
            maxLength={120}
            disabled={busy}
            readOnly={Boolean(reconnecting)}
            placeholder={ui("e.g. Team Google account")}
            autoComplete="off"
          />
        )}
      </form.AppField>
      {reconnecting ? (
        <Alert variant="warning" role="note">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            {ui("Reconnecting affects all")} {reconnecting.sourceCount}{" "}
            {ui(
              "Sources using this credential, not just one Source. Use the same Google account. Saved links and indexed documents are retained.",
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {reconnectingCredential?.oauthClientConfigured && canReplaceClient ? (
        <Field orientation="horizontal">
          <Checkbox
            id="google-drive-credential-replace-client"
            checked={replaceClient}
            disabled={busy}
            onCheckedChange={(event) => {
              clientInput.current?.clear();
              setClientReady(false);
              setReplaceClient(event === true);
            }}
          />
          <FieldLabel htmlFor="google-drive-credential-replace-client">
            {ui("Replace OAuth app on reconnect")}
          </FieldLabel>
        </Field>
      ) : null}
      {needsClient ? (
        <GoogleDriveOAuthClientInput
          ref={clientInput}
          disabled={busy || !canManage}
          onReadyChange={setClientReady}
        />
      ) : missingClient ? (
        <Alert variant="warning" role="note">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            {ui(
              "This credential has no saved OAuth app. Ask a tenant administrator with global Source management permission to add the app and reconnect it, or create a new credential with your own OAuth app.",
            )}
          </AlertDescription>
        </Alert>
      ) : (
        <p className="text-sm text-content-secondary">
          {ui("Reconnect reuses the OAuth app saved with this credential.")}
        </p>
      )}
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
          busy || unavailable || missingClient || !name.trim() || (needsClient && !clientReady)
        }
      >
        {ui("Authenticate")}
      </Button>
    </form>
  );
}
