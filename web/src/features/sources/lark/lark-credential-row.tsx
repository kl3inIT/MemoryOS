import { useAppTranslation } from "@/i18n/use-app-translation";
import { revalidateLogic } from "@tanstack/react-form";
import { Ellipsis } from "lucide-react";
import { useEffect, useRef } from "react";
import { useAppForm, setServerErrors, useProblemErrors } from "@/components/form/app-form";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { RadioGroupItem } from "@/components/ui/radio-group";
import { StatusBadge } from "@/components/ui/status-badge";
import { TableBody, TableCell, TableRow } from "@/components/ui/table";
import type { LarkCredentialResponse } from "@/lib/hey-api/types.gen";
import { zRenameLarkCredentialRequest } from "@/lib/hey-api/zod.gen";
import { sourceMutationError } from "@/features/sources/shared/source-errors";
import { LarkMissingScopes } from "./lark-app-guide";
import type { LarkCredentials } from "./use-lark-credentials";

/** One credential as a row group: its row, then its actions while it is being managed. */
export function LarkCredentialRow({
  credential,
  selected,
  disabled,
  manager,
}: {
  credential: LarkCredentialResponse;
  selected: boolean;
  disabled: boolean;
  manager: LarkCredentials;
}) {
  const ui = useAppTranslation();
  const ready = credential.status === "ACTIVE";
  const managed = manager.managedId === credential.id && credential.actions.length > 0;
  const actionsId = `lark-credential-actions-${credential.id}`;

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
              <span className="block font-medium wrap-anywhere text-content-primary">
                {credential.name}
              </span>
              <span className="block font-mono text-xs wrap-anywhere text-content-secondary">
                {credential.appId}
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
          <span className="block text-xs wrap-anywhere text-content-secondary">
            {credential.botName ?? ui("Not resolved yet")}
          </span>
        </TableCell>
        <TableCell>
          {credential.missingScopes.length === 0 ? (
            <StatusBadge tone="success">{ui("Granted")}</StatusBadge>
          ) : (
            <StatusBadge tone="warning">
              {ui("{{count}} missing", { count: credential.missingScopes.length })}
            </StatusBadge>
          )}
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
  credential: LarkCredentialResponse;
  disabled: boolean;
  manager: LarkCredentials;
}) {
  const ui = useAppTranslation();
  const locked = disabled || manager.busy;
  return (
    <div className="flex flex-col gap-3 py-1">
      <p className="text-sm text-content-secondary">
        {ui("Used by")} {credential.sourceCount}{" "}
        {credential.sourceCount === 1 ? ui("Source") : ui("Sources")}.
      </p>
      {credential.missingScopes.length > 0 ? (
        <LarkMissingScopes scopes={credential.missingScopes} />
      ) : null}
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
        {credential.actions.includes("replace_secret") ? (
          <Button
            prominence="tertiary"
            disabled={locked}
            onClick={(event) => manager.openDialog(event.currentTarget, credential)}
          >
            {ui("Replace App Secret")}
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
              "Permanently delete this unused credential and its stored App Secret. Credentials attached to any Source cannot be deleted.",
            )}
            confirmLabel={ui("Delete credential")}
            pendingLabel={ui("Deleting")}
            onConfirm={() => manager.deleteCredential(credential)}
            errorMessage={(cause) => sourceMutationError(cause, "lark-credential")}
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
  credential: LarkCredentialResponse;
  manager: LarkCredentials;
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
      onDynamic: zRenameLarkCredentialRequest.extend({
        name: zRenameLarkCredentialRequest.shape.name.trim().min(1, ui("Enter a name.")),
      }),
    },
    onSubmit: async ({ value, formApi }) => {
      try {
        await manager.saveName(credential, value.name);
      } catch (cause) {
        setServerErrors(formApi, problemErrors(cause));
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
