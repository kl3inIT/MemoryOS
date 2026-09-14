import { uiLocale } from "@/i18n/format";
import type { AppCopy } from "@/i18n/app-text";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMutation } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronRight, Pencil } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { OnyxUserManageIcon, OnyxUsersIcon } from "@/components/icons/identity-icons";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { sameOriginMutationHeaders } from "@/lib/api";
import { renameGroupMutation } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";

type GroupCardProps = {
  group: GroupSummary;
  onAuthorityChanged: () => Promise<void>;
};
export function GroupCard({ group, onAuthorityChanged }: GroupCardProps) {
  const ui = useAppTranslation();

  const renameGroup = useMutation(renameGroupMutation());
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(group.name);
  const [error, setError] = useState<AppCopy | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const canRename = group.systemKey === null && group.actions.includes("rename");
  const [previousRenameState, setPreviousRenameState] = useState(() => ({
    groupId: group.id,
    name: group.name,
    canRename,
  }));
  const builtIn = group.systemKey !== null;
  const description =
    group.systemKey === "ADMIN"
      ? "Built-in admin group with full access to manage all permissions."
      : group.systemKey === "BASIC"
        ? "Default group for all users with basic permissions."
        : "Custom group for member access and permissions.";

  useEffect(() => {
    if (editing) inputRef.current?.focus();
  }, [editing]);

  if (
    previousRenameState.groupId !== group.id ||
    previousRenameState.name !== group.name ||
    previousRenameState.canRename !== canRename
  ) {
    setPreviousRenameState({ groupId: group.id, name: group.name, canRename });
    if (!canRename || previousRenameState.groupId !== group.id) {
      setEditing(false);
      setName(group.name);
      setError(null);
    }
  }

  async function saveName() {
    const nextName = name.trim();
    if (!canRename || !nextName || nextName === group.name || renameGroup.isPending) return;
    setError(null);
    try {
      await renameGroup.mutateAsync({
        path: { groupId: group.id },
        headers: sameOriginMutationHeaders,
        body: { name: nextName },
      });
      setEditing(false);
      editButtonRef.current?.focus();
      await onAuthorityChanged();
    } catch (cause) {
      setError(groupMutationError(cause, "rename"));
    }
  }

  function cancelEdit() {
    setName(group.name);
    setError(null);
    setEditing(false);
    requestAnimationFrame(() => editButtonRef.current?.focus());
  }

  return (
    <article className="groups-list-card group">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 size-5 shrink-0 text-content-secondary">
          {group.systemKey === "ADMIN" ? (
            <OnyxUserManageIcon className="size-5" aria-hidden="true" />
          ) : (
            <OnyxUsersIcon className="size-5" aria-hidden="true" />
          )}
        </span>
        <div className="min-w-0 flex-1">
          {editing && canRename ? (
            <form
              className="flex flex-col gap-2 sm:flex-row"
              onSubmit={(event) => {
                event.preventDefault();
                void saveName();
              }}
            >
              <Input
                ref={inputRef}
                value={name}
                maxLength={120}
                aria-label={ui("Name for {{v1}}", { v1: group.name })}
                disabled={renameGroup.isPending}
                onChange={(event) => setName(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Escape") {
                    event.preventDefault();
                    cancelEdit();
                  }
                }}
              />
              <div className="flex gap-2">
                <Button
                  type="submit"
                  size="sm"
                  pending={renameGroup.isPending}
                  disabled={!name.trim() || name.trim() === group.name}
                >
                  {renameGroup.isPending ? ui("Saving…") : ui("Save")}
                </Button>
                <Button
                  size="sm"
                  prominence="secondary"
                  disabled={renameGroup.isPending}
                  onClick={cancelEdit}
                >
                  {ui("Cancel")}
                </Button>
              </div>
            </form>
          ) : (
            <div className="flex min-w-0 items-center gap-2">
              <h2 className="truncate text-base font-semibold leading-5 text-content-primary">
                {group.name}
              </h2>
              {builtIn ? (
                <span className="groups-default-tag shrink-0">{ui("Default")}</span>
              ) : null}
              {canRename ? (
                <IconButton
                  ref={editButtonRef}
                  size="sm"
                  prominence="tertiary"
                  aria-label={ui("Rename {{v1}}", { v1: group.name })}
                  className="size-5 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100"
                  onClick={() => {
                    setName(group.name);
                    setEditing(true);
                  }}
                >
                  <Pencil />
                </IconButton>
              ) : null}
            </div>
          )}
          <p className="mt-1 text-xs leading-4 text-content-secondary">{ui(description)}</p>
          {error ? (
            <p role="alert" className="mt-3 font-secondary-body text-status-danger-content">
              {ui(error)}
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 items-start gap-2">
          <span className="pt-0.5 text-sm leading-5 tabular-nums text-content-secondary">
            {group.memberCount.toLocaleString(uiLocale())}{" "}
            {group.memberCount === 1 ? ui("Member") : ui("Members")}
          </span>
          <IconButton
            asChild
            size="sm"
            prominence="internal"
            aria-label={ui("Open {{v1}}", { v1: group.name })}
            className="-mt-1 -mr-1 text-content-muted"
          >
            <Link to="/admin/groups/$groupId" params={{ groupId: group.id }}>
              <ChevronRight className="size-4" />
            </Link>
          </IconButton>
        </div>
      </div>
    </article>
  );
}
