import { useAppTranslation } from "@/i18n/use-app-translation";
import { MoreHorizontal, RefreshCw, UserCheck, UserX, Users, XCircle } from "lucide-react";
import { useRef, useState, type RefObject } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { UserListItem } from "@/lib/hey-api/types.gen";
import { invitationError, membershipActionError } from "./user-action-errors";
import { userActionPendingLabel, type UserPendingAction } from "./use-user-actions";

type ConfirmationAction = "activate" | "deactivate" | "revoke";

type UserRowActionsProps = {
  entry: UserListItem;
  pendingAction?: UserPendingAction;
  invitationPending: boolean;
  membershipChangesView: boolean;
  canEditGroups: boolean;
  fallbackFocusRef?: RefObject<HTMLElement | null>;
  onEditGroups: (returnTarget: HTMLButtonElement | null) => void;
  onActivate: (entry: UserListItem) => Promise<void>;
  onDeactivate: (entry: UserListItem) => Promise<void>;
  onRotate: (entry: UserListItem, returnTarget: HTMLButtonElement | null) => Promise<void>;
  onRevoke: (entry: UserListItem) => Promise<void>;
};

export function UserRowActions({
  entry,
  pendingAction,
  invitationPending,
  membershipChangesView,
  canEditGroups,
  fallbackFocusRef,
  onEditGroups,
  onActivate,
  onDeactivate,
  onRotate,
  onRevoke,
}: UserRowActionsProps) {
  const ui = useAppTranslation();

  const [menuOpen, setMenuOpen] = useState(false);
  const [confirmation, setConfirmation] = useState<ConfirmationAction | null>(null);
  const actionButtonRef = useRef<HTMLButtonElement>(null);
  const label =
    entry.displayName?.trim() ||
    entry.email?.trim() ||
    (entry.actorId ? ui("user {{id}}", { id: entry.actorId }) : ui("this invitation"));
  const canChangeMembership =
    entry.role === "MEMBER" &&
    Boolean(entry.actorId) &&
    (entry.status === "ACTIVE" || entry.status === "INACTIVE");
  const canManageInvitation = entry.status === "INVITED" && Boolean(entry.invitationId);
  const canChangeGroups = canEditGroups && Boolean(entry.actorId);
  const actionable = canChangeMembership || canManageInvitation || canChangeGroups;

  if (!actionable) {
    return (
      <span
        aria-label={ui("No actions available for {{v1}}", { v1: label })}
        className="font-main-ui-body text-content-muted"
      >
        —
      </span>
    );
  }

  const confirmationTitle =
    confirmation === "activate"
      ? ui("Activate {{name}}?", { name: label })
      : confirmation === "deactivate"
        ? ui("Deactivate {{name}}?", { name: label })
        : ui("Revoke the invitation for {{name}}?", { name: label });
  const confirmationDescription =
    confirmation === "activate"
      ? "They will regain access to this organization. Their existing identity and membership history stay intact."
      : confirmation === "deactivate"
        ? "They will lose access to this organization right away. You can activate them again at any time; their identity and membership history stay intact."
        : "The current recovery link will stop working. You can invite this email again later.";
  const confirmLabel =
    confirmation === "activate"
      ? "Activate member"
      : confirmation === "deactivate"
        ? "Deactivate member"
        : "Revoke invitation";
  const confirmPendingLabel =
    confirmation === "activate"
      ? "Activating…"
      : confirmation === "deactivate"
        ? "Deactivating…"
        : "Revoking…";

  return (
    <>
      <DropdownMenu
        open={menuOpen}
        // A running action keeps the menu shut; closing is always allowed, since choosing an entry starts one.
        onOpenChange={(nextOpen) => setMenuOpen(nextOpen && !pendingAction)}
      >
        <DropdownMenuTrigger asChild>
          <IconButton
            ref={actionButtonRef}
            size="sm"
            prominence="tertiary"
            aria-label={
              pendingAction
                ? ui("{{v1}} for {{v2}}", { v1: userActionPendingLabel(pendingAction), v2: label })
                : ui("Actions for {{v1}}", { v1: label })
            }
            pending={Boolean(pendingAction)}
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          {canChangeGroups ? (
            <>
              <DropdownMenuItem onSelect={() => onEditGroups(actionButtonRef.current)}>
                <Users />
                {ui("Edit groups")}
              </DropdownMenuItem>
              {canChangeMembership ? <DropdownMenuSeparator /> : null}
            </>
          ) : null}
          {canChangeMembership ? (
            entry.status === "ACTIVE" ? (
              <DropdownMenuItem
                variant="destructive"
                onSelect={() => setConfirmation("deactivate")}
              >
                <UserX />
                {ui("Deactivate member")}
              </DropdownMenuItem>
            ) : (
              <DropdownMenuItem onSelect={() => setConfirmation("activate")}>
                <UserCheck />
                {ui("Activate member")}
              </DropdownMenuItem>
            )
          ) : canManageInvitation ? (
            <>
              <DropdownMenuItem
                disabled={invitationPending}
                onSelect={() =>
                  void onRotate(entry, actionButtonRef.current).catch(() => undefined)
                }
              >
                <RefreshCw />
                {ui("Rotate recovery link")}
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <DropdownMenuItem variant="destructive" onSelect={() => setConfirmation("revoke")}>
                <XCircle />
                {ui("Revoke invitation")}
              </DropdownMenuItem>
            </>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>

      <ConfirmDialog
        open={confirmation !== null}
        onOpenChange={(nextOpen) => {
          if (!nextOpen) setConfirmation(null);
        }}
        restoreFocusRef={actionButtonRef}
        successFocusRef={
          confirmation === "revoke" || membershipChangesView ? fallbackFocusRef : undefined
        }
        fallbackFocusRef={fallbackFocusRef}
        title={confirmationTitle}
        description={ui(confirmationDescription)}
        confirmLabel={ui(confirmLabel)}
        pendingLabel={ui(confirmPendingLabel)}
        confirmTone={confirmation === "activate" ? "default" : "danger"}
        onConfirm={() => {
          if (confirmation === "activate") return onActivate(entry);
          if (confirmation === "deactivate") return onDeactivate(entry);
          if (confirmation === "revoke") return onRevoke(entry);
          return Promise.resolve();
        }}
        errorMessage={(error) =>
          confirmation === "revoke" ? invitationError(error) : membershipActionError(error)
        }
      />
    </>
  );
}
