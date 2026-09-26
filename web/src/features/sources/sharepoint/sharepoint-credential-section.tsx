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
import {
  deleteSharePointCredentialMutation,
  listSharePointCredentialsOptions,
  listSharePointCredentialsQueryKey,
  renameSharePointCredentialMutation,
  testSharePointCredentialMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { SharePointCredentialResponse } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { SharePointCredentialDialog } from "./sharepoint-credential-dialog";

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
  const modalTrigger = useRef<HTMLButtonElement | null>(null);
  const active = useRef(true);
  const [dialog, setDialog] = useState<{
    open: boolean;
    session: number;
    replacing: SharePointCredentialResponse | null;
  }>({ open: false, session: 0, replacing: null });
  const [saving, setSaving] = useState(false);
  const [renaming, setRenaming] = useState<SharePointCredentialResponse | null>(null);
  const [managedId, setManagedId] = useState<string | null>(null);
  const [error, setError] = useState<AppCopy | null>(null);
  const [testedId, setTestedId] = useState<string | null>(null);
  // Captured once per mount; a credential's expiry does not need to tick live.
  const [now] = useState(() => Date.now());
  const busy = saving || remove.isPending || rename.isPending || test.isPending;
  const unavailable = credentials.isPending || credentials.isError;

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);

  useLayoutEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);

  function openDialog(trigger: HTMLButtonElement, replacing: SharePointCredentialResponse | null) {
    if (busy) return;
    modalTrigger.current = trigger;
    setError(null);
    setDialog((current) => ({ open: true, session: current.session + 1, replacing }));
  }

  // The dialog has no trigger of its own, so it only ever asks to close.
  function changeDialog(open: boolean) {
    if (busy || open) return;
    setError(null);
    setDialog((current) => ({ ...current, open: false, replacing: null }));
  }

  async function credentialSaved(credential: SharePointCredentialResponse) {
    setDialog((current) => ({ ...current, open: false, replacing: null }));
    onSelect(credential.id);
    await credentials.refetch();
  }

  async function runTest(credential: SharePointCredentialResponse) {
    if (busy || !credential.actions.includes("test")) return;
    setError(null);
    setTestedId(null);
    try {
      const result = await test.mutateAsync({
        path: { credentialId: credential.id },
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
                                if (credential.actions.includes("replace_authentication"))
                                  openDialog(event.currentTarget, credential);
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
        {error && !dialog.open ? (
          <p role="alert" className="mt-4 text-sm text-status-danger-content">
            {ui(error)}
          </p>
        ) : null}
        <Button
          className="mt-6"
          disabled={disabled || busy || unavailable}
          onClick={(event) => openDialog(event.currentTarget, null)}
        >
          {ui("Create New")}
        </Button>
      </section>

      <SharePointCredentialDialog
        open={dialog.open}
        session={dialog.session}
        replacing={dialog.replacing}
        busy={busy}
        triggerRef={modalTrigger}
        onOpenChange={changeDialog}
        onBusyChange={setSaving}
        onSaved={credentialSaved}
      />
    </>
  );
}
