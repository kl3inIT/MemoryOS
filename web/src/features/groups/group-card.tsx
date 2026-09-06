import { useMutation } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { ChevronRight, Pencil, ShieldCheck, UsersRound } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
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
  const renameGroup = useMutation(renameGroupMutation());
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(group.name);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const canRename = group.actions.includes("rename");
  const builtIn = group.systemKey !== null;

  useEffect(() => {
    if (editing) inputRef.current?.focus();
  }, [editing]);

  async function saveName() {
    const nextName = name.trim();
    if (!nextName || nextName === group.name || renameGroup.isPending) return;
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
    <article className="rounded-xl border border-border-subtle bg-surface-raised p-4 shadow-xs transition-colors hover:border-border-default sm:p-5">
      <div className="flex items-start gap-3">
        <span className="grid size-10 shrink-0 place-items-center rounded-xl border border-border-subtle bg-surface-subtle text-content-secondary">
          {group.systemKey === "ADMIN" ? (
            <ShieldCheck className="size-5" aria-hidden="true" />
          ) : (
            <UsersRound className="size-5" aria-hidden="true" />
          )}
        </span>
        <div className="min-w-0 flex-1">
          {editing ? (
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
                aria-label={`Name for ${group.name}`}
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
                  {renameGroup.isPending ? "Saving…" : "Save"}
                </Button>
                <Button
                  size="sm"
                  prominence="secondary"
                  disabled={renameGroup.isPending}
                  onClick={cancelEdit}
                >
                  Cancel
                </Button>
              </div>
            </form>
          ) : (
            <div className="flex min-w-0 items-center gap-2">
              <h2 className="truncate font-heading-h3 text-content-primary">{group.name}</h2>
              {builtIn ? (
                <Badge
                  variant="outline"
                  className="shrink-0 bg-surface-raised text-content-secondary"
                >
                  System
                </Badge>
              ) : null}
              {canRename ? (
                <IconButton
                  ref={editButtonRef}
                  size="sm"
                  prominence="tertiary"
                  aria-label={`Rename ${group.name}`}
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
          <p className="mt-1 font-main-ui-body text-content-muted">
            {group.memberCount.toLocaleString()} {group.memberCount === 1 ? "member" : "members"}
            {group.managerCount > 0
              ? ` · ${group.managerCount.toLocaleString()} ${group.managerCount === 1 ? "manager" : "managers"}`
              : ""}
          </p>
          {group.capabilities.length > 0 ? (
            <div
              className="mt-3 flex flex-wrap gap-1"
              aria-label={`Capabilities for ${group.name}`}
            >
              {group.capabilities.slice(0, 3).map((capability) => (
                <Badge
                  key={capability}
                  variant="secondary"
                  className="bg-surface-subtle text-content-secondary"
                >
                  {capability.replaceAll("_", " ").toLocaleLowerCase()}
                </Badge>
              ))}
              {group.capabilities.length > 3 ? (
                <Badge variant="outline" className="text-content-muted">
                  +{group.capabilities.length - 3}
                </Badge>
              ) : null}
            </div>
          ) : (
            <p className="mt-3 font-secondary-body text-content-muted">
              No administrative capabilities
            </p>
          )}
          {error ? (
            <p role="alert" className="mt-3 font-secondary-body text-status-danger-content">
              {error}
            </p>
          ) : null}
        </div>
        <IconButton asChild size="sm" prominence="tertiary" aria-label={`Open ${group.name}`}>
          <Link to="/admin/groups/$groupId" params={{ groupId: group.id }}>
            <ChevronRight />
          </Link>
        </IconButton>
      </div>
    </article>
  );
}
