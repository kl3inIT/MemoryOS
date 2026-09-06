import {
  LoaderCircle,
  MoreHorizontal,
  RefreshCw,
  UserRoundCheck,
  UserRoundX,
  UsersRound,
  XCircle,
} from "lucide-react";
import { useRef, useState, type RefObject } from "react";
import { Popover } from "radix-ui";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { MenuItem } from "@/components/ui/menu-item";
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
  const [menuOpen, setMenuOpen] = useState(false);
  const [confirmation, setConfirmation] = useState<ConfirmationAction | null>(null);
  const actionButtonRef = useRef<HTMLButtonElement>(null);
  const label =
    entry.displayName?.trim() ||
    entry.email?.trim() ||
    (entry.actorId ? `user ${entry.actorId}` : "this invitation");
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
        aria-label={`No actions available for ${label}`}
        className="font-main-ui-body text-content-muted"
      >
        —
      </span>
    );
  }

  const confirmationTitle =
    confirmation === "activate"
      ? `Activate ${label}?`
      : confirmation === "deactivate"
        ? `Deactivate ${label}?`
        : `Revoke the invitation for ${label}?`;
  const confirmationDescription =
    confirmation === "activate"
      ? "They will regain access to this Tenant. Their existing identity and membership history stay intact."
      : confirmation === "deactivate"
        ? "They will lose access on their next protected request. Their identity and membership history stay intact."
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
      <Popover.Root
        open={menuOpen}
        onOpenChange={(nextOpen) => {
          if (!pendingAction) setMenuOpen(nextOpen);
        }}
      >
        <Popover.Trigger asChild>
          <IconButton
            ref={actionButtonRef}
            size="sm"
            prominence="tertiary"
            aria-label={
              pendingAction
                ? `${userActionPendingLabel(pendingAction)} for ${label}`
                : `Actions for ${label}`
            }
            pending={Boolean(pendingAction)}
          >
            {pendingAction ? (
              <LoaderCircle className="animate-spin motion-reduce:animate-none" />
            ) : (
              <MoreHorizontal />
            )}
          </IconButton>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            align="end"
            sideOffset={6}
            className="z-30 w-56 rounded-xl border border-border-default bg-surface-overlay p-1.5 shadow-md outline-none data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none"
          >
            {canChangeGroups ? (
              <>
                <MenuItem
                  icon={<UsersRound className="size-4.5" />}
                  onClick={() => {
                    setMenuOpen(false);
                    onEditGroups(actionButtonRef.current);
                  }}
                >
                  Edit groups
                </MenuItem>
                {canChangeMembership ? (
                  <div className="my-1 border-t border-border-subtle" />
                ) : null}
              </>
            ) : null}
            {canChangeMembership ? (
              entry.status === "ACTIVE" ? (
                <MenuItem
                  tone="danger"
                  icon={<UserRoundX className="size-4.5" />}
                  onClick={() => {
                    setMenuOpen(false);
                    setConfirmation("deactivate");
                  }}
                >
                  Deactivate member
                </MenuItem>
              ) : (
                <MenuItem
                  icon={<UserRoundCheck className="size-4.5" />}
                  onClick={() => {
                    setMenuOpen(false);
                    setConfirmation("activate");
                  }}
                >
                  Activate member
                </MenuItem>
              )
            ) : canManageInvitation ? (
              <>
                <MenuItem
                  icon={<RefreshCw className="size-4.5" />}
                  disabled={invitationPending}
                  onClick={() => {
                    setMenuOpen(false);
                    void onRotate(entry, actionButtonRef.current).catch(() => undefined);
                  }}
                >
                  Rotate recovery link
                </MenuItem>
                <div className="my-1 border-t border-border-subtle" />
                <MenuItem
                  tone="danger"
                  icon={<XCircle className="size-4.5" />}
                  onClick={() => {
                    setMenuOpen(false);
                    setConfirmation("revoke");
                  }}
                >
                  Revoke invitation
                </MenuItem>
              </>
            ) : null}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>

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
        description={confirmationDescription}
        confirmLabel={confirmLabel}
        pendingLabel={confirmPendingLabel}
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
