import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic } from "@tanstack/react-form";
import { Ellipsis } from "lucide-react";
import { useEffect, useRef } from "react";
import { useAppForm, useProblemErrors } from "@/components/form/app-form";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { RadioGroupItem } from "@/components/ui/radio-group";
import { StatusBadge } from "@/components/ui/status-badge";
import { TableBody, TableCell, TableRow } from "@/components/ui/table";
import type { SharePointCredentialResponse } from "@/lib/hey-api/types.gen";
import { zRenameSharePointCredentialRequest } from "@/lib/hey-api/zod.gen";
import { cn } from "@/lib/utils";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import type { SharePointCredentials } from "./use-sharepoint-credentials";

const expiryWarningMs = 30 * 24 * 60 * 60 * 1000;

/** One credential as a row group: its row, then its actions while it is being managed. */
export function SharePointCredentialRow({
  credential,
  selected,
  disabled,
  now,
  manager,
}: {
  credential: SharePointCredentialResponse;
  selected: boolean;
  disabled: boolean;
  /** When the page opened; a credential's expiry does not need to tick live. */
  now: number;
  manager: SharePointCredentials;
}) {
  const ui = useAppTranslation();
  const ready = credential.status === "ACTIVE";
  const expiry = credential.certificateNotAfter ? new Date(credential.certificateNotAfter) : null;
  const expiringSoon = expiry !== null && expiry.getTime() - now < expiryWarningMs;
  const managed = manager.managedId === credential.id && credential.actions.length > 0;
  const actionsId = `sharepoint-credential-actions-${credential.id}`;

  return (
    <TableBody>
      <TableRow data-state={selected && ready ? "selected" : undefined}>
        <TableCell>
          <RadioGroupItem
            value={credential.id}
            aria-label={ui("Select {{v1}}", { v1: credential.name })}
            disabled={!ready || disabled || manager.busy}
          />
        </TableCell>
        <TableCell>
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
              ) : manager.testedId === credential.id ? (
                <span className="mt-1 block">
                  <StatusBadge tone="success">{ui("Verified just now")}</StatusBadge>
                </span>
              ) : null}
            </div>
            {credential.actions.length > 0 ? (
              <IconButton
                aria-label={ui("Manage {{v1}}", { v1: credential.name })}
                aria-expanded={managed}
                aria-controls={actionsId}
                title={ui("Manage credential")}
                onClick={() => manager.toggleManaged(credential.id)}
              >
                <Ellipsis />
              </IconButton>
            ) : null}
          </div>
        </TableCell>
        <TableCell>
          <span className="text-xs text-content-secondary">
            {credential.authMethod === "CERTIFICATE" ? ui("Certificate") : ui("Client secret")}
            {expiry ? (
              <span className={cn("mt-1 block", expiringSoon && "text-status-warning-content")}>
                {ui("Expires {{v1}}", { v1: expiry.toLocaleDateString(uiLocale()) })}
              </span>
            ) : null}
          </span>
        </TableCell>
        <TableCell>
          <span className="block wrap-anywhere text-xs text-content-secondary">
            {credential.tenantHost ?? ui("Not resolved yet")}
          </span>
        </TableCell>
      </TableRow>
      {managed ? (
        <TableRow id={actionsId}>
          <TableCell colSpan={4}>
            <CredentialActions credential={credential} disabled={disabled} manager={manager} />
          </TableCell>
        </TableRow>
      ) : null}
    </TableBody>
  );
}

function CredentialActions({
  credential,
  disabled,
  manager,
}: {
  credential: SharePointCredentialResponse;
  disabled: boolean;
  manager: SharePointCredentials;
}) {
  const ui = useAppTranslation();
  const locked = disabled || manager.busy;
  return (
    <div className="flex flex-col gap-3 py-1">
      <p className="text-sm text-content-secondary">
        {ui("Used by")} {credential.sourceCount}{" "}
        {credential.sourceCount === 1 ? ui("Source") : ui("Sources")}.
      </p>
      {manager.renaming === credential.id ? (
        <RenameForm credential={credential} manager={manager} />
      ) : null}
      <div className="flex flex-wrap gap-2">
        {credential.actions.includes("test") ? (
          <Button
            prominence="tertiary"
            disabled={locked}
            pending={manager.testPending}
            onClick={() => void manager.runTest(credential)}
          >
            {ui("Test")}
          </Button>
        ) : null}
        {credential.actions.includes("rename") ? (
          <Button
            prominence="tertiary"
            disabled={locked}
            onClick={() => manager.setRenaming(credential.id)}
          >
            {ui("Rename")}
          </Button>
        ) : null}
        {credential.actions.includes("replace_authentication") ? (
          <Button
            prominence="tertiary"
            disabled={locked}
            onClick={(event) => manager.openDialog(event.currentTarget, credential)}
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
                disabled={locked || credential.sourceCount !== 0}
                title={
                  credential.sourceCount
                    ? ui("Delete all attached Sources before deleting this credential")
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
            onConfirm={() => manager.deleteCredential(credential)}
            errorMessage={(cause) => sourceMutationError(cause, "sharepoint-credential")}
          />
        ) : null}
      </div>
    </div>
  );
}

function RenameForm({
  credential,
  manager,
}: {
  credential: SharePointCredentialResponse;
  manager: SharePointCredentials;
}) {
  const ui = useAppTranslation();
  const problemErrors = useProblemErrors();
  const container = useRef<HTMLFormElement>(null);
  // The name field opens on request, so it takes focus as it appears.
  useEffect(() => container.current?.querySelector("input")?.focus(), []);
  const form = useAppForm({
    defaultValues: { name: credential.name },
    validationLogic: revalidateLogic(),
    validators: {
      onDynamic: zRenameSharePointCredentialRequest.extend({
        name: zRenameSharePointCredentialRequest.shape.name.trim().min(1, ui("Enter a name.")),
      }),
      // A new attempt clears the previous attempt's server errors.
      onSubmit: () => undefined,
    },
    onSubmit: async ({ value, formApi }) => {
      try {
        await manager.saveName(credential, value.name);
      } catch (cause) {
        formApi.setErrorMap({ onSubmit: problemErrors(cause) });
      }
    },
  });
  return (
    <form
      ref={container}
      className="flex flex-wrap items-end gap-2"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void form.handleSubmit();
      }}
    >
      <div className="min-w-48 flex-1">
        <form.AppField name="name">
          {(field) => <field.TextField label={ui("Name")} maxLength={120} />}
        </form.AppField>
      </div>
      <form.AppForm>
        <form.SubmitButton disabled={manager.busy}>{ui("Save name")}</form.SubmitButton>
      </form.AppForm>
      <Button
        prominence="tertiary"
        disabled={manager.busy}
        onClick={() => manager.setRenaming(null)}
      >
        {ui("Cancel")}
      </Button>
      <form.AppForm>
        <div className="basis-full">
          <form.FormError />
        </div>
      </form.AppForm>
    </form>
  );
}
