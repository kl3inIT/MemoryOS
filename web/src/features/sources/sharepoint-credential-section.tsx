import { appText } from "@/i18n/app-text";
import { uiLocale } from "@/i18n/format";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Ellipsis, KeyRound } from "lucide-react";
import { useLayoutEffect, useRef, useState } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
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
import { isUnauthenticated, sameOriginMutationHeaders } from "@/lib/api";
import {
  deleteSharePointCredentialMutation,
  getCurrentIdentityQueryKey,
  listSharePointCredentialsOptions,
  listSharePointCredentialsQueryKey,
  renameSharePointCredentialMutation,
  testSharePointCredentialMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import {
  createSharePointCredential,
  replaceSharePointCredentialAuthentication,
} from "@/lib/hey-api/sdk.gen";
import type { SharePointCredentialResponse } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "./source-errors";
import { SharePointEntraGuide } from "./sharepoint-entra-guide";
import {
  SharePointCredentialInput,
  type SharePointAuthMethod,
  type SharePointCredentialInputHandle,
} from "./sharepoint-credential-input";

const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The credential step: pick a verified Entra application, or register one. */
export function SharePointCredentialSection({
  selectedId,
  disabled = false,
  onSelect,
  onBusyChange,
}: {
  selectedId: string | undefined;
  disabled?: boolean;
  onSelect: (credentialId: string) => void;
  onBusyChange?: (busy: boolean) => void;
}) {
  const ui = useAppTranslation();

  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const credentials = useQuery({ ...listSharePointCredentialsOptions(), retry: false });
  const remove = useMutation(deleteSharePointCredentialMutation());
  const rename = useMutation(renameSharePointCredentialMutation());
  const test = useMutation(testSharePointCredentialMutation());
  const credentialInput = useRef<SharePointCredentialInputHandle>(null);
  const modalTrigger = useRef<HTMLButtonElement | null>(null);
  const active = useRef(true);
  const submitting = useRef(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [replacing, setReplacing] = useState<SharePointCredentialResponse | null>(null);
  const [renaming, setRenaming] = useState<SharePointCredentialResponse | null>(null);
  const [managedId, setManagedId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [directoryId, setDirectoryId] = useState("");
  const [clientId, setClientId] = useState("");
  const [method, setMethod] = useState<SharePointAuthMethod>("CLIENT_SECRET");
  const [authenticationReady, setAuthenticationReady] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<AppCopy | null>(null);
  const [testedId, setTestedId] = useState<string | null>(null);
  // Captured once per mount; a credential's expiry does not need to tick live.
  const [now] = useState(() => Date.now());
  const busy = saving || remove.isPending || rename.isPending || test.isPending;
  const unavailable = credentials.isPending || credentials.isError;

  useLayoutEffect(() => {
    active.current = true;
    const input = credentialInput.current;
    return () => {
      active.current = false;
      input?.clear();
    };
  }, []);

  useLayoutEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);

  function changeModal(open: boolean) {
    if (busy) return;
    credentialInput.current?.clear();
    setAuthenticationReady(false);
    setError(null);
    if (!open) {
      setName("");
      setDirectoryId("");
      setClientId("");
      setMethod("CLIENT_SECRET");
      setReplacing(null);
    }
    setModalOpen(open);
  }

  function replaceAuthentication(credential: SharePointCredentialResponse) {
    if (!credential.actions.includes("replace_authentication")) return;
    changeModal(true);
    setReplacing(credential);
    setName(credential.name);
    setDirectoryId(credential.directoryId);
    setClientId(credential.clientId);
    setMethod(credential.authMethod === "CERTIFICATE" ? "CERTIFICATE" : "CLIENT_SECRET");
  }

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
              ...sameOriginMutationHeaders,
              "If-Match": `"${replacing.credentialRevision}"`,
            },
            body,
            throwOnError: true,
          })
        : await createSharePointCredential({
            headers: sameOriginMutationHeaders,
            body,
            throwOnError: true,
          });
      if (!active.current) return;
      notify({
        title: replacing ? "Credential updated" : "Credential verified",
        description: replacing
          ? `${data.name} was verified with Microsoft and its authentication replaced.`
          : `${data.name} was verified with Microsoft and is ready to use with a Source.`,
        tone: "success",
      });
      changeModal(false);
      onSelect(data.id);
      await credentials.refetch();
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

  async function runTest(credential: SharePointCredentialResponse) {
    if (busy || !credential.actions.includes("test")) return;
    setError(null);
    setTestedId(null);
    try {
      const result = await test.mutateAsync({
        path: { credentialId: credential.id },
        headers: sameOriginMutationHeaders,
      });
      if (!active.current) return;
      setTestedId(credential.id);
      notify({
        title: "Credential works",
        description: result.allSitesReadable
          ? appText("{{v1}} can read this Tenant's sites.", { v1: credential.name })
          : appText("{{v1}} works, but it cannot list every site. Name each site in the scope.", {
              v1: credential.name,
            }),
        tone: result.allSitesReadable ? "success" : "info",
      });
    } catch (cause) {
      if (!active.current) return;
      setError(sourceMutationError(cause, "sharepoint-credential"));
    } finally {
      await credentials.refetch();
    }
  }

  async function saveName(credential: SharePointCredentialResponse, value: string) {
    await rename.mutateAsync({
      path: { credentialId: credential.id },
      headers: {
        ...sameOriginMutationHeaders,
        "If-Match": `"${credential.credentialRevision}"`,
      },
      body: { name: value.trim() },
    });
    if (!active.current) return;
    setRenaming(null);
    await credentials.refetch();
  }

  async function deleteCredential(credential: SharePointCredentialResponse) {
    if (!credential.actions.includes("delete") || credential.sourceCount !== 0)
      throw new Error("Credential is unavailable");
    await remove.mutateAsync({
      path: { credentialId: credential.id },
      headers: {
        ...sameOriginMutationHeaders,
        "If-Match": `"${credential.credentialRevision}"`,
      },
    });
    if (!active.current) return;
    notify({
      title: "Credential deleted",
      description: appText("{{v1}} and its stored authentication were deleted.", {
        v1: credential.name,
      }),
      tone: "success",
    });
    await Promise.all([
      credentials.refetch(),
      queryClient.invalidateQueries({ queryKey: listSharePointCredentialsQueryKey() }),
    ]);
  }

  return (
    <>
      <section
        aria-labelledby="sharepoint-credential-heading"
        className="rounded-2xl border border-border-default bg-surface-base p-6"
      >
        <h2
          id="sharepoint-credential-heading"
          className="pb-2 font-heading-h3 text-content-primary"
        >
          {ui("Select a credential")}
        </h2>
        <p className="mb-4 text-sm text-content-secondary">
          {ui(
            "MemoryOS signs in as an Entra application, so there is no consent screen and no reader account.",
          )}
        </p>
        <RadioGroup
          value={selectedId ?? ""}
          onValueChange={(value) => {
            setError(null);
            onSelect(value);
          }}
        >
          <Table className="w-full table-fixed text-sm">
            <TableCaption className="sr-only">{ui("SharePoint credentials")}</TableCaption>
            <TableHeader className="hidden bg-surface-raised text-xs text-content-secondary sm:table-header-group">
              <TableRow>
                <TableHead scope="col" className="w-12 py-3">
                  <span className="sr-only">{ui("Select")}</span>
                </TableHead>
                <TableHead scope="col" className="px-2 py-3 text-left font-medium">
                  {ui("Name")}
                </TableHead>
                <TableHead scope="col" className="w-[22%] px-2 py-3 text-left font-medium">
                  {ui("Authentication")}
                </TableHead>
                <TableHead scope="col" className="w-[22%] px-2 py-3 text-left font-medium">
                  {ui("SharePoint host")}
                </TableHead>
              </TableRow>
            </TableHeader>
            {credentials.data?.map((credential) => {
              const ready = credential.status === "ACTIVE";
              const expiry = credential.certificateNotAfter
                ? new Date(credential.certificateNotAfter)
                : null;
              const expiringSoon =
                expiry !== null && expiry.getTime() - now < 30 * 24 * 60 * 60 * 1000;
              return (
                <TableBody
                  key={credential.id}
                  className="block border-b border-border-subtle sm:table-row-group"
                >
                  <TableRow
                    className={`grid grid-cols-[2.75rem_minmax(0,1fr)] gap-x-2 gap-y-2 py-3 sm:table-row ${
                      credential.id === selectedId && ready ? "bg-surface-subtle" : ""
                    }`}
                  >
                    <TableCell className="order-first py-2 align-middle">
                      <label className="flex size-11 cursor-pointer items-center justify-center has-disabled:cursor-default">
                        <RadioGroupItem
                          value={credential.id}
                          aria-label={ui("Select {{v1}}", { v1: credential.name })}
                          disabled={!ready || disabled || busy}
                        />
                      </label>
                    </TableCell>
                    <TableCell className="order-first px-2 py-2 align-middle">
                      <div className="flex items-center gap-2">
                        <div className="min-w-0 flex-1 text-sm">
                          <span className="block wrap-anywhere font-medium text-content-primary">
                            {credential.name}
                          </span>
                          <span className="block font-mono text-xs text-content-secondary">
                            {credential.clientId.slice(0, 8)}
                          </span>
                          {!ready ? (
                            <span className="mt-1 block">
                              <StatusBadge tone="warning">{ui("Needs update")}</StatusBadge>
                            </span>
                          ) : testedId === credential.id ? (
                            <span className="mt-1 block">
                              <StatusBadge tone="success">{ui("Verified just now")}</StatusBadge>
                            </span>
                          ) : null}
                        </div>
                        {credential.actions.length > 0 ? (
                          <IconButton
                            aria-label={ui("Manage {{v1}}", { v1: credential.name })}
                            aria-expanded={managedId === credential.id}
                            aria-controls={`sharepoint-credential-actions-${credential.id}`}
                            title={ui("Manage credential")}
                            className="size-11"
                            onClick={() =>
                              setManagedId(managedId === credential.id ? null : credential.id)
                            }
                          >
                            <Ellipsis />
                          </IconButton>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="col-start-2 px-2 py-2 align-middle text-xs text-content-secondary">
                      <span className="mb-1 block sm:hidden">{ui("Authentication")}</span>
                      {credential.authMethod === "CERTIFICATE"
                        ? ui("Certificate")
                        : ui("Client secret")}
                      {expiry ? (
                        <span
                          className={`mt-1 block ${expiringSoon ? "text-status-warning-content" : ""}`}
                        >
                          {ui("Expires {{v1}}", { v1: expiry.toLocaleDateString(uiLocale()) })}
                        </span>
                      ) : null}
                    </TableCell>
                    <TableCell className="col-start-2 px-2 py-2 align-middle text-xs text-content-secondary">
                      <span className="mb-1 block sm:hidden">{ui("SharePoint host")}</span>
                      <span className="block wrap-anywhere">
                        {credential.tenantHost ?? ui("Not resolved yet")}
                      </span>
                    </TableCell>
                  </TableRow>
                  {managedId === credential.id && credential.actions.length > 0 ? (
                    <TableRow
                      id={`sharepoint-credential-actions-${credential.id}`}
                      className="block sm:table-row"
                    >
                      <TableCell colSpan={4} className="block px-2 py-3 sm:table-cell">
                        <p className="mb-2 text-sm text-content-secondary">
                          {ui("Used by")} {credential.sourceCount}{" "}
                          {credential.sourceCount === 1 ? ui("Source") : ui("Sources")}.
                        </p>
                        {renaming?.id === credential.id ? (
                          <form
                            className="mb-3 flex flex-wrap items-end gap-2"
                            onSubmit={(event) => {
                              event.preventDefault();
                              const value = new FormData(event.currentTarget).get("name");
                              if (typeof value === "string" && value.trim())
                                void saveName(credential, value).catch((cause: unknown) =>
                                  setError(sourceMutationError(cause, "sharepoint-credential")),
                                );
                            }}
                          >
                            <label className="min-w-48 flex-1">
                              <span className="block font-secondary-action">{ui("Name")}</span>
                              <Input
                                name="name"
                                defaultValue={credential.name}
                                maxLength={120}
                                required
                                autoFocus
                                className="mt-1"
                              />
                            </label>
                            <Button type="submit" pending={rename.isPending} disabled={busy}>
                              {ui("Save name")}
                            </Button>
                            <Button
                              prominence="tertiary"
                              disabled={busy}
                              onClick={() => setRenaming(null)}
                            >
                              {ui("Cancel")}
                            </Button>
                          </form>
                        ) : null}
                        <div className="flex flex-wrap gap-2">
                          {credential.actions.includes("test") ? (
                            <Button
                              prominence="tertiary"
                              disabled={disabled || busy}
                              pending={test.isPending}
                              onClick={() => void runTest(credential)}
                            >
                              {ui("Test")}
                            </Button>
                          ) : null}
                          {credential.actions.includes("rename") ? (
                            <Button
                              prominence="tertiary"
                              disabled={disabled || busy}
                              onClick={() => setRenaming(credential)}
                            >
                              {ui("Rename")}
                            </Button>
                          ) : null}
                          {credential.actions.includes("replace_authentication") ? (
                            <Button
                              prominence="tertiary"
                              disabled={disabled || busy}
                              onClick={(event) => {
                                modalTrigger.current = event.currentTarget;
                                replaceAuthentication(credential);
                              }}
                            >
                              {ui("Replace authentication")}
                            </Button>
                          ) : null}
                          {credential.actions.includes("delete") ? (
                            <ConfirmDialog
                              trigger={
                                <Button
                                  tone="danger"
                                  prominence="tertiary"
                                  disabled={disabled || busy || credential.sourceCount !== 0}
                                  title={
                                    credential.sourceCount
                                      ? ui(
                                          "Delete all attached Sources before deleting this credential",
                                        )
                                      : undefined
                                  }
                                >
                                  {ui("Delete")}
                                </Button>
                              }
                              title={ui("Delete {{v1}}?", { v1: credential.name })}
                              description={ui(
                                "Permanently delete this unused credential and its stored authentication. Credentials attached to any Source cannot be deleted.",
                              )}
                              confirmLabel={ui("Delete credential")}
                              pendingLabel={ui("Deleting")}
                              onConfirm={() => deleteCredential(credential)}
                              errorMessage={(cause) =>
                                sourceMutationError(cause, "sharepoint-credential")
                              }
                            />
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
        {credentials.isPending ? (
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
        ) : !credentials.data?.length ? (
          <Empty className="mt-4 gap-3 border border-dashed border-border-default py-8">
            <EmptyMedia variant="icon">
              <KeyRound />
            </EmptyMedia>
            <EmptyHeader>
              <EmptyTitle className="font-main-ui-action">
                {ui("No SharePoint credentials yet")}
              </EmptyTitle>
              <EmptyDescription>
                {ui(
                  "Register the Entra application once, then every SharePoint Source in this Tenant can use it.",
                )}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        ) : null}
        {error && !modalOpen ? (
          <p role="alert" className="mt-4 text-sm text-status-danger-content">
            {ui(error)}
          </p>
        ) : null}
        <Button
          className="mt-6"
          disabled={disabled || busy || unavailable}
          onClick={(event) => {
            modalTrigger.current = event.currentTarget;
            changeModal(true);
          }}
        >
          {ui("Create New")}
        </Button>
      </section>

      <Dialog open={modalOpen} onOpenChange={changeModal}>
        <DialogContent
          className="sm:max-w-4xl"
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
                  <span className="grid size-8 place-items-center rounded-lg bg-surface-sunken text-content-secondary">
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
        </DialogContent>
      </Dialog>
    </>
  );
}
