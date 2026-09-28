import { useAppTranslation } from "@/i18n/use-app-translation";
import { MoreHorizontal, RefreshCw, UserCheck, UserX, Users, XCircle } from "lucide-react";
import { useRef, useState, type RefObject } from "react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { MenuItem } from "@/components/ui/menu-item";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
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
      <Popover
        open={menuOpen}
        onOpenChange={(nextOpen) => {
          if (!pendingAction) setMenuOpen(nextOpen);
        }}
      >
        <PopoverTrigger asChild>
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
        </PopoverTrigger>
        <PopoverContent align="end" sideOffset={6} className="w-56 p-1.5">
          <div className="flex flex-col gap-1">
            {canChangeGroups ? (
              <>
                <MenuItem
                  icon={<Users />}
                  onClick={() => {
                    setMenuOpen(false);
                    onEditGroups(actionButtonRef.current);
                  }}
                >
                  {ui("Edit groups")}
                </MenuItem>
                {canChangeMembership ? <Separator /> : null}
              </>
            ) : null}
            {canChangeMembership ? (
              entry.status === "ACTIVE" ? (
                <MenuItem
                  tone="danger"
                  icon={<UserX />}
                  onClick={() => {
                    setMenuOpen(false);
                    setConfirmation("deactivate");
                  }}
                >
                  {ui("Deactivate member")}
                </MenuItem>
              ) : (
                <MenuItem
                  icon={<UserCheck />}
                  onClick={() => {
                    setMenuOpen(false);
                    setConfirmation("activate");
                  }}
                >
                  {ui("Activate member")}
                </MenuItem>
              )
            ) : canManageInvitation ? (
              <>
                <MenuItem
                  icon={<RefreshCw />}
                  disabled={invitationPending}
                  onClick={() => {
                    setMenuOpen(false);
                    void onRotate(entry, actionButtonRef.current).catch(() => undefined);
                  }}
                >
                  {ui("Rotate recovery link")}
                </MenuItem>
                <Separator />
                <MenuItem
                  tone="danger"
                  icon={<XCircle />}
                  onClick={() => {
                    setMenuOpen(false);
                    setConfirmation("revoke");
                  }}
                >
                  {ui("Revoke invitation")}
                </MenuItem>
              </>
            ) : null}
          </div>
        </PopoverContent>
      </Popover>

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
