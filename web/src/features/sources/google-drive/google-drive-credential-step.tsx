import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation, useQueryClient, type UseQueryResult } from "@tanstack/react-query";
import { ArrowRight, Plus, TriangleAlert } from "lucide-react";
import { useEffect, useLayoutEffect, useRef } from "react";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { SetupSection } from "@/features/sources/shared/setup-section";
import { googleDriveCredentialReady } from "./google-drive-credential";
import { GoogleDriveCredentialActions } from "./google-drive-credential-actions";

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
        // Sources on a revoked credential read their state afresh when they are next opened.
        queryClient.invalidateQueries({ queryKey: listSourcesQueryKey() }),
      ]);
    }
  }

  return (
    <SetupSection labelledBy="credential-heading">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
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
          <Plus data-icon="inline-start" aria-hidden="true" />
          {ui("Create New")}
        </Button>
      </div>
      <div>
        <TooltipProvider>
          <RadioGroup value={credentialId ?? ""} onValueChange={onSelect}>
            {/* One row group per credential, so each credential's controls stay together. */}
            <Table aria-labelledby="credential-table-caption" className="min-w-3xl table-fixed">
              <TableCaption id="credential-table-caption" className="sr-only">
                {ui("Google Drive credentials")}
              </TableCaption>
              <colgroup>
                <col className="w-14" />
                <col className="w-24" />
                <col />
                <col className="w-56" />
                <col className="w-32" />
                <col className="w-28" />
                <col className="w-28" />
                <col className="w-14" />
              </colgroup>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">
                    <span className="sr-only">{ui("Select")}</span>
                  </TableHead>
                  <TableHead scope="col">{ui("ID")}</TableHead>
                  <TableHead scope="col">{ui("Name")}</TableHead>
                  <TableHead scope="col">{ui("Account")}</TableHead>
                  <TableHead scope="col">{ui("Status")}</TableHead>
                  <TableHead scope="col">{ui("Created")}</TableHead>
                  <TableHead scope="col">{ui("Last Updated")}</TableHead>
                  <TableHead scope="col">
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
          <Alert variant="destructive" className="mt-4">
            <AlertDescription>
              {ui("Credentials could not be loaded. Refresh before making changes.")}
            </AlertDescription>
            <div className="mt-2">
              <Button
                size="sm"
                prominence="secondary"
                pending={credentials.isFetching}
                onClick={() => void credentials.refetch()}
              >
                {ui("Try again")}
              </Button>
            </div>
          </Alert>
        ) : canManage && !credentials.data?.length ? (
          <p className="mt-4 text-sm text-content-primary">
            {ui("No credentials exist for this connector!")}
          </p>
        ) : null}
        {credentialId && !selected && !unavailable ? (
          <Alert variant="warning" className="mt-4">
            <TriangleAlert aria-hidden="true" />
            <AlertDescription>
              {ui(
                "The selected credential is no longer available. Select another credential or create a new one.",
              )}
            </AlertDescription>
          </Alert>
        ) : null}
      </div>
      <footer className="flex justify-end border-t border-border-subtle pt-5">
        <Button disabled={unavailable || busy || !connected} onClick={onContinue}>
          {ui("Continue")}
          <ArrowRight data-icon="inline-end" aria-hidden="true" />
        </Button>
      </footer>
    </SetupSection>
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
  const account = serviceAccount
    ? (credential.serviceAccountEmail ?? credential.accountEmail)
    : credential.accountEmail;
  return (
    <TableBody>
      <TableRow data-state={selected && ready ? "selected" : undefined}>
        <TableCell>
          <RadioGroupItem
            value={credential.id}
            aria-label={ui("Select {{v1}}", { v1: credential.name })}
            disabled={!ready || disabled}
          />
        </TableCell>
        <TableCell>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                className="cursor-default rounded-sm font-mono text-xs outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                {credential.id.slice(0, 8)}
              </button>
            </TooltipTrigger>
            <TooltipContent>
              <span className="font-mono">{credential.id}</span>
            </TooltipContent>
          </Tooltip>
        </TableCell>
        <TableCell>
          <span className="block wrap-anywhere text-sm font-medium text-content-primary">
            {credential.name}
          </span>
        </TableCell>
        <TableCell>
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                className="block max-w-full cursor-default truncate rounded-sm text-left text-sm text-content-secondary outline-none focus-visible:ring-2 focus-visible:ring-focus-ring"
              >
                {account}
              </button>
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
        <TableCell>
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
        <TableCell>
          <time dateTime={credential.createdAt} className="text-xs text-content-secondary">
            {new Date(credential.createdAt).toLocaleDateString(uiLocale())}
          </time>
        </TableCell>
        <TableCell>
          <time dateTime={credential.updatedAt} className="text-xs text-content-secondary">
            {new Date(credential.updatedAt).toLocaleDateString(uiLocale())}
          </time>
        </TableCell>
        <TableCell className="text-right">
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
