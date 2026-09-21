import { KeyRound, MoreHorizontal, RefreshCw, Trash2, Unplug } from "lucide-react";
import { useRef, useState } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import type { GoogleDriveCredentialResponse } from "@/lib/hey-api";
import type { ErrorMessage } from "@/lib/problem-presentation";

/**
 * Row actions of one Google Drive credential. Reconnecting and replacing a key open the setup
 * modal, so they hand back the trigger the modal must restore focus to.
 */
export function GoogleDriveCredentialActions({
  credential,
  disabled,
  onReconnect,
  onReplaceKey,
  onRevoke,
  onDelete,
  errorMessage,
}: {
  credential: GoogleDriveCredentialResponse;
  disabled: boolean;
  onReconnect: (trigger: HTMLButtonElement | null) => void;
  onReplaceKey: (trigger: HTMLButtonElement | null) => void;
  onRevoke: () => Promise<void>;
  onDelete: () => Promise<void>;
  errorMessage: (cause: unknown) => AppCopy | ErrorMessage;
}) {
  const ui = useAppTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const openingDialog = useRef(false);
  const [confirming, setConfirming] = useState<"reconnect" | "revoke" | "delete" | null>(null);
  const serviceAccount = credential.authMethod === "SERVICE_ACCOUNT";
  const attached = credential.sourceCount > 0;
  const canReconnect = credential.actions.includes("reauthorize");
  const canReplaceKey = credential.actions.includes("replace_key");
  const canRevoke = credential.actions.includes("revoke") && credential.status !== "REVOKED";
  const canDelete = credential.actions.includes("delete");
  if (!canReconnect && !canReplaceKey && !canRevoke && !canDelete) return null;

  function confirm(action: "reconnect" | "revoke" | "delete") {
    openingDialog.current = true;
    setConfirming(action);
  }

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            ref={trigger}
            size="sm"
            disabled={disabled}
            aria-label={ui("Manage {{v1}}", { v1: credential.name })}
          >
            <MoreHorizontal aria-hidden="true" />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent
          align="end"
          className="w-auto min-w-48"
          onCloseAutoFocus={(event) => {
            if (!openingDialog.current) return;
            openingDialog.current = false;
            event.preventDefault();
          }}
        >
          <DropdownMenuLabel className="font-normal text-content-muted">
            {ui("Used by")} {credential.sourceCount}{" "}
            {credential.sourceCount === 1 ? ui("Source") : ui("Sources")}
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          {canReconnect ? (
            <DropdownMenuItem
              onSelect={() => (attached ? confirm("reconnect") : onReconnect(trigger.current))}
            >
              <RefreshCw aria-hidden="true" />
              {ui("Reconnect")}
            </DropdownMenuItem>
          ) : null}
          {canReplaceKey ? (
            <DropdownMenuItem onSelect={() => onReplaceKey(trigger.current)}>
              <KeyRound aria-hidden="true" />
              {ui("Replace key")}
            </DropdownMenuItem>
          ) : null}
          {canRevoke ? (
            <DropdownMenuItem variant="destructive" onSelect={() => confirm("revoke")}>
              <Unplug aria-hidden="true" />
              {ui("Revoke")}
            </DropdownMenuItem>
          ) : null}
          {canDelete ? (
            <DropdownMenuItem
              variant="destructive"
              disabled={attached}
              onSelect={() => confirm("delete")}
            >
              <Trash2 aria-hidden="true" />
              {ui("Delete")}
            </DropdownMenuItem>
          ) : null}
          {canDelete && attached ? (
            <DropdownMenuLabel className="max-w-56 font-normal text-wrap text-content-muted">
              {ui("Delete all attached Sources before deleting this credential")}
            </DropdownMenuLabel>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
      <ConfirmDialog
        open={confirming === "reconnect"}
        onOpenChange={(open) => setConfirming(open ? "reconnect" : null)}
        restoreFocusRef={trigger}
        title={ui("Reconnect shared Google credential?")}
        description={ui(
          "Reconnecting changes the authorization used by all {{v1}} Sources, including other Sources. Use the same Google account. Saved links and indexed documents are retained.",
          { v1: credential.sourceCount },
        )}
        confirmLabel={ui("Reconnect")}
        pendingLabel={ui("Reconnecting")}
        onConfirm={() => {
          onReconnect(trigger.current);
          return Promise.resolve();
        }}
        errorMessage={errorMessage}
      />
      <ConfirmDialog
        open={confirming === "revoke"}
        onOpenChange={(open) => setConfirming(open ? "revoke" : null)}
        restoreFocusRef={trigger}
        title={ui("Revoke {{v1}}?", { v1: credential.name })}
        description={
          serviceAccount
            ? ui(
                "This destroys the saved key and stops synchronization for all {{v1}} Sources using this credential. Saved links and documents are retained. Replace the key of the same service account to resume.",
                { v1: credential.sourceCount },
              )
            : ui(
                "This stops synchronization for all {{v1}} Sources using this credential. Saved links and documents are retained. Reconnect the same Google account to resume.",
                { v1: credential.sourceCount },
              )
        }
        confirmLabel={ui("Revoke")}
        pendingLabel={ui("Revoking")}
        onConfirm={onRevoke}
        errorMessage={errorMessage}
      />
      <ConfirmDialog
        open={confirming === "delete"}
        onOpenChange={(open) => setConfirming(open ? "delete" : null)}
        restoreFocusRef={trigger}
        title={ui("Delete {{v1}}?", { v1: credential.name })}
        description={
          serviceAccount
            ? ui(
                "Permanently delete this unused credential and its saved service account key. Credentials attached to any Source cannot be deleted.",
              )
            : ui(
                "Permanently delete this unused credential and its saved OAuth app. You will need to authorize again to use it. Credentials attached to any Source cannot be deleted.",
              )
        }
        confirmLabel={ui("Delete credential")}
        pendingLabel={ui("Deleting")}
        onConfirm={onDelete}
        errorMessage={errorMessage}
      />
    </>
  );
}
