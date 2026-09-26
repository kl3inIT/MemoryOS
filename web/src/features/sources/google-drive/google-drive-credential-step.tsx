import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ArrowRight, Plus } from "lucide-react";
import { useEffect, useLayoutEffect, useRef } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Button } from "@/components/ui/button";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
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
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import {
  deleteGoogleDriveCredentialMutation,
  listSourcesQueryKey,
  revokeGoogleDriveCredentialMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api/types.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { googleDriveCredentialReady } from "./google-drive-credential";
import { GoogleDriveCredentialActions } from "./google-drive-credential-actions";

/** One bordered block per group of settings, as in Vanta's integration setup. */
export const sectionCard = "space-y-5 rounded-2xl border border-border-default bg-surface-base p-6";

/** The first setup step: choose a connected credential, create one, or manage the existing ones. */
export function GoogleDriveCredentialStep({
  credentials,
  credentialId,
  canManage,
  unavailable,
  connected,
  busy: pageBusy,
  frozen,
  onSelect,
  onCreate,
  onReconnect,
  onReplaceKey,
  onContinue,
  onBusyChange,
}: {
  credentials: UseQueryResult<GoogleDriveCredentialResponse[]>;
  credentialId: string | undefined;
  canManage: boolean;
  unavailable: boolean;
  /** Whether the chosen credential is connected and can create a Source. */
  connected: boolean;
  /** Other work on the page. */
  busy: boolean;
  /** A submitted proposal holds its credential still. */
  frozen: boolean;
  onSelect: (credentialId: string) => void;
  onCreate: (trigger: HTMLButtonElement) => void;
  onReconnect: (
    trigger: HTMLButtonElement | null,
    credential: GoogleDriveCredentialResponse,
  ) => void;
  onReplaceKey: (
    trigger: HTMLButtonElement | null,
    credential: GoogleDriveCredentialResponse,
  ) => void;
  onContinue: () => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const ui = useAppTranslation();
  const queryClient = useQueryClient();
  const notify = useActionNotifications();
  const revoke = useMutation(revokeGoogleDriveCredentialMutation());
  const remove = useMutation(deleteGoogleDriveCredentialMutation());
  const submitting = useRef(false);
  const active = useRef(true);
  const busy = pageBusy || revoke.isPending || remove.isPending;
  const selected = credentials.data?.find((credential) => credential.id === credentialId);
  const locked = unavailable || busy || frozen;

  useLayoutEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const changing = revoke.isPending || remove.isPending;
  useEffect(() => {
    onBusyChange(changing);
    return () => onBusyChange(false);
  }, [changing, onBusyChange]);

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
          body: { expectedCredentialRevision: credential.credentialRevision },
        });
      } else {
        await remove.mutateAsync({
          path: { credentialId: credential.id },
          headers: {
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

  return (
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
          disabled={locked}
          onClick={(event) => onCreate(event.currentTarget)}
        >
          <Plus /> {ui("Create New")}
        </Button>
      </div>
      <div>
        <TooltipProvider>
          <RadioGroup value={credentialId ?? ""} onValueChange={onSelect}>
            <Table className="w-full table-fixed text-sm">
              <TableCaption className="sr-only">{ui("Google Drive credentials")}</TableCaption>
              <TableHeader className="hidden border-b border-border-subtle text-xs text-content-muted sm:table-header-group">
                <TableRow>
                  <TableHead scope="col" className="w-12 py-3">
                    <span className="sr-only">{ui("Select")}</span>
                  </TableHead>
                  <TableHead scope="col" className="w-[10%] px-2 py-3 text-left font-medium">
                    {ui("ID")}
                  </TableHead>
                  <TableHead scope="col" className="px-2 py-3 text-left font-medium">
                    {ui("Name")}
                  </TableHead>
                  <TableHead scope="col" className="w-[26%] px-2 py-3 text-left font-medium">
                    {ui("Account")}
                  </TableHead>
                  <TableHead scope="col" className="w-[13%] px-2 py-3 text-left font-medium">
                    {ui("Status")}
                  </TableHead>
                  <TableHead scope="col" className="w-[12%] px-2 py-3 text-left font-medium">
                    {ui("Created")}
                  </TableHead>
                  <TableHead scope="col" className="w-[13%] px-2 py-3 text-left font-medium">
                    {ui("Last Updated")}
                  </TableHead>
                  <TableHead scope="col" className="w-14 py-3">
                    <span className="sr-only">{ui("Actions")}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              {credentials.data?.map((credential) => (
                <CredentialRow
                  key={credential.id}
                  credential={credential}
                  selected={credential.id === credentialId}
                  disabled={locked}
                  onReconnect={(trigger) => onReconnect(trigger, credential)}
                  onReplaceKey={(trigger) => onReplaceKey(trigger, credential)}
                  onRevoke={() => changeCredential(credential, "revoke")}
                  onDelete={() => changeCredential(credential, "delete")}
                />
              ))}
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
        <Button disabled={unavailable || busy || !connected} onClick={onContinue}>
          {ui("Continue")} <ArrowRight />
        </Button>
      </footer>
    </section>
  );
}

function CredentialRow({
  credential,
  selected,
  disabled,
  onReconnect,
  onReplaceKey,
  onRevoke,
  onDelete,
}: {
  credential: GoogleDriveCredentialResponse;
  selected: boolean;
  disabled: boolean;
  onReconnect: (trigger: HTMLButtonElement | null) => void;
  onReplaceKey: (trigger: HTMLButtonElement | null) => void;
  onRevoke: () => Promise<void>;
  onDelete: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  const ready = googleDriveCredentialReady(credential);
  const serviceAccount = credential.authMethod === "SERVICE_ACCOUNT";
  return (
    <TableBody className="block border-b border-border-subtle sm:table-row-group">
      <TableRow
        className={`grid grid-cols-[2.75rem_minmax(0,1fr)_2.75rem] gap-x-2 gap-y-2 py-3 sm:table-row ${
          selected && ready ? "bg-surface-subtle" : ""
        }`}
      >
        <TableCell className="order-first py-2 align-middle">
          <label className="flex size-11 cursor-pointer items-center justify-center has-disabled:cursor-default">
            <RadioGroupItem
              value={credential.id}
              aria-label={ui("Select {{v1}}", { v1: credential.name })}
              disabled={!ready || disabled}
            />
          </label>
        </TableCell>
        <TableCell className="col-span-3 px-2 py-2 align-middle">
          <span className="mr-2 text-xs text-content-secondary sm:hidden">{ui("ID")}</span>
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={0} className="font-mono text-xs">
                {credential.id.slice(0, 8)}
              </span>
            </TooltipTrigger>
            <TooltipContent className="font-mono">{credential.id}</TooltipContent>
          </Tooltip>
        </TableCell>
        <TableCell className="order-first px-2 py-2 align-middle">
          <span className="block wrap-anywhere text-sm font-medium text-content-primary">
            {credential.name}
          </span>
        </TableCell>
        <TableCell className="col-span-2 col-start-2 px-2 py-2 align-middle text-sm text-content-secondary">
          <span className="mr-2 sm:hidden">{ui("Account")}</span>
          <Tooltip>
            <TooltipTrigger asChild>
              <span tabIndex={0} className="inline-block max-w-full truncate align-bottom">
                {serviceAccount
                  ? (credential.serviceAccountEmail ?? credential.accountEmail)
                  : credential.accountEmail}
              </span>
            </TooltipTrigger>
            <TooltipContent>
              {serviceAccount
                ? ui("Service account {{v1}} acting as {{v2}}", {
                    v1: credential.serviceAccountEmail ?? "",
                    v2: credential.accountEmail,
                  })
                : credential.accountEmail}
            </TooltipContent>
          </Tooltip>
        </TableCell>
        <TableCell className="col-span-2 col-start-2 px-2 py-2 align-middle">
          <StatusBadge tone={ready ? "success" : "warning"}>
            {ready
              ? ui("Connected")
              : credential.status === "REVOKED"
                ? ui("Revoked")
                : serviceAccount
                  ? ui("Needs a new key")
                  : ui("Needs reconnect")}
          </StatusBadge>
        </TableCell>
        <TableCell className="col-span-2 col-start-2 px-2 py-2 align-middle text-xs text-content-secondary">
          <span className="mr-2 sm:hidden">{ui("Created")}</span>
          <time dateTime={credential.createdAt}>
            {new Date(credential.createdAt).toLocaleDateString(uiLocale())}
          </time>
        </TableCell>
        <TableCell className="col-span-2 col-start-2 px-2 py-2 align-middle text-xs text-content-secondary">
          <span className="mr-2 sm:hidden">{ui("Last Updated")}</span>
          <time dateTime={credential.updatedAt}>
            {new Date(credential.updatedAt).toLocaleDateString(uiLocale())}
          </time>
        </TableCell>
        <TableCell className="order-first pr-1 pl-0 text-right align-middle">
          <GoogleDriveCredentialActions
            credential={credential}
            disabled={disabled}
            onReconnect={onReconnect}
            onReplaceKey={onReplaceKey}
            onRevoke={onRevoke}
            onDelete={onDelete}
            errorMessage={(cause) => sourceMutationError(cause, "google-drive")}
          />
        </TableCell>
      </TableRow>
    </TableBody>
  );
}
