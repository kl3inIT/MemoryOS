import { ArrowDown, ArrowUp, ArrowUpDown, LoaderCircle, UserRound } from "lucide-react";
import { Fragment, useRef, useState, type RefObject } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
import type { UserListItem } from "@/lib/hey-api/types.gen";
import { GroupTags } from "./group-tags";
import { UserGroupsDialog } from "./user-groups-dialog";
import { UserRowActions } from "./user-row-actions";
import { userActionPendingLabel, type UserPendingAction } from "./use-user-actions";
import type { UsersSearch, UsersSort } from "./users-search";

type UsersTableProps = {
  entries: UserListItem[];
  sort: UsersSort;
  statusFilter?: UsersSearch["status"];
  page: number;
  size: UsersSearch["size"];
  totalItems: number;
  totalPages: number;
  pendingActions: Readonly<Partial<Record<string, UserPendingAction>>>;
  rowErrors: Readonly<Partial<Record<string, string>>>;
  invitationPending: boolean;
  canEditGroups: boolean;
  fallbackActionFocusRef?: RefObject<HTMLElement | null>;
  onGroupsSaved: () => Promise<void>;
  onSortChange: (sort: UsersSort) => void;
  onPageChange: (page: number) => void;
  onSizeChange: (size: UsersSearch["size"]) => void;
  onActivate: (entry: UserListItem) => Promise<void>;
  onDeactivate: (entry: UserListItem) => Promise<void>;
  onRotate: (entry: UserListItem, returnTarget: HTMLButtonElement | null) => Promise<void>;
  onRevoke: (entry: UserListItem) => Promise<void>;
};

const statusTone: Record<UserListItem["status"], StatusTone> = {
  ACTIVE: "success",
  INACTIVE: "neutral",
  INVITED: "warning",
};

export function UsersTable({
  entries,
  sort,
  statusFilter,
  page,
  size,
  totalItems,
  totalPages,
  pendingActions,
  rowErrors,
  invitationPending,
  canEditGroups,
  fallbackActionFocusRef,
  onGroupsSaved,
  onSortChange,
  onPageChange,
  onSizeChange,
  onActivate,
  onDeactivate,
  onRotate,
  onRevoke,
}: UsersTableProps) {
  const firstItem = totalItems === 0 ? 0 : page * size + 1;
  const lastItem = Math.min((page + 1) * size, totalItems);
  const [groupEditorEntry, setGroupEditorEntry] = useState<UserListItem | null>(null);
  const groupEditorFocusRef = useRef<HTMLElement | null>(null);

  function openGroupEditor(entry: UserListItem, returnTarget: HTMLElement | null) {
    if (!entry.actorId || !canEditGroups) return;
    groupEditorFocusRef.current = returnTarget;
    setGroupEditorEntry(entry);
  }

  function closeGroupEditor() {
    const target = groupEditorFocusRef.current;
    setGroupEditorEntry(null);
    groupEditorFocusRef.current = null;
    if (target?.isConnected) requestAnimationFrame(() => target.focus());
  }

  return (
    <>
      <div
        role="region"
        aria-label="Scrollable users table"
        tabIndex={0}
        className="overflow-x-auto outline-none focus-visible:ring-3 focus-visible:ring-inset focus-visible:ring-focus-ring/40"
      >
        <table className="w-full min-w-[56rem] table-fixed border-collapse">
          <caption className="sr-only">Tenant users</caption>
          <colgroup>
            <col />
            <col className="w-[28%]" />
            <col className="w-[16%]" />
            <col className="w-[18%]" />
            <col className="w-16" />
          </colgroup>
          <thead className="border-b border-border-subtle bg-surface-subtle text-left">
            <tr>
              <th
                scope="col"
                aria-sort={columnAriaSort(sort, "NAME", "EMAIL")}
                className="h-11 px-4"
              >
                <span className="inline-flex items-center gap-0.5">
                  <UsersSortButton
                    field="NAME"
                    label="Name"
                    sort={sort}
                    onSortChange={onSortChange}
                  />
                  <span className="font-secondary-action text-content-muted" aria-hidden="true">
                    /
                  </span>
                  <UsersSortButton
                    field="EMAIL"
                    label="Email"
                    sort={sort}
                    onSortChange={onSortChange}
                  />
                </span>
              </th>
              <StaticColumnHeader>Groups</StaticColumnHeader>
              <StaticColumnHeader>Account type</StaticColumnHeader>
              <th scope="col" aria-sort={columnAriaSort(sort, "STATUS")} className="h-11 px-4">
                <UsersSortButton
                  field="STATUS"
                  label="Status"
                  sort={sort}
                  onSortChange={onSortChange}
                />
              </th>
              <th
                scope="col"
                className="sticky right-0 z-10 h-11 w-16 bg-surface-subtle px-2 text-center"
              >
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {entries.map((entry) => {
              const key = entry.actorId
                ? `actor:${entry.actorId}`
                : `invitation:${entry.invitationId}`;
              const pendingAction = pendingActions[key];
              const error = rowErrors[key];
              const label = userLabel(entry);
              const editableGroups = canEditGroups && Boolean(entry.actorId);
              return (
                <Fragment key={key}>
                  <tr className="group bg-surface-raised align-middle transition-colors hover:bg-surface-subtle">
                    <td className="h-[4.5rem] px-4 py-3">
                      <UserIdentity entry={entry} />
                    </td>
                    <td className="px-4 py-3">
                      <GroupTags
                        groups={entry.groups}
                        editable={editableGroups}
                        userLabel={label}
                        onEdit={(target) => openGroupEditor(entry, target)}
                      />
                    </td>
                    <td className="px-4 py-3">
                      {entry.accountType === "STANDARD" ? (
                        <span className="inline-flex items-center gap-1.5 font-main-ui-body text-content-secondary">
                          <UserRound className="size-4 text-content-muted" aria-hidden="true" />
                          Standard
                        </span>
                      ) : (
                        <span
                          aria-label="Account type assigned after invitation acceptance"
                          className="text-content-muted"
                        >
                          —
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <UserStatus entry={entry} pendingAction={pendingAction} />
                    </td>
                    <td className="sticky right-0 w-16 bg-surface-raised px-2 py-3 text-center transition-colors group-hover:bg-surface-subtle">
                      <UserRowActions
                        entry={entry}
                        pendingAction={pendingAction}
                        invitationPending={invitationPending}
                        membershipChangesView={Boolean(statusFilter) || sort.startsWith("STATUS_")}
                        canEditGroups={canEditGroups}
                        fallbackFocusRef={fallbackActionFocusRef}
                        onEditGroups={(target) => openGroupEditor(entry, target)}
                        onActivate={onActivate}
                        onDeactivate={onDeactivate}
                        onRotate={onRotate}
                        onRevoke={onRevoke}
                      />
                    </td>
                  </tr>
                  {error ? (
                    <tr className="bg-status-danger-surface/60">
                      <td
                        colSpan={5}
                        className="border-l-2 border-status-danger-content px-4 py-2 font-secondary-body text-status-danger-content"
                      >
                        <p role="alert">{error}</p>
                      </td>
                    </tr>
                  ) : null}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      <nav
        aria-label="User pages"
        className="flex flex-col gap-3 border-t border-border-subtle px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
      >
        <p className="font-secondary-body tabular-nums text-content-muted">
          Showing {firstItem}–{lastItem} of {totalItems}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-2 font-secondary-body text-content-secondary">
            Rows
            <Select
              aria-label="Rows per page"
              value={size}
              size="sm"
              className="w-auto px-2"
              onChange={(event) => onSizeChange(Number(event.target.value) as UsersSearch["size"])}
            >
              <option value={20}>20</option>
              <option value={50}>50</option>
              <option value={100}>100</option>
            </Select>
          </label>
          <span className="min-w-24 text-center font-secondary-body tabular-nums text-content-secondary">
            Page {page + 1} of {Math.max(totalPages, 1)}
          </span>
          <Button
            size="sm"
            prominence="secondary"
            disabled={page <= 0}
            onClick={() => onPageChange(page - 1)}
          >
            Previous
          </Button>
          <Button
            size="sm"
            prominence="secondary"
            disabled={page + 1 >= totalPages}
            onClick={() => onPageChange(page + 1)}
          >
            Next
          </Button>
        </div>
      </nav>

      {groupEditorEntry ? (
        <UserGroupsDialog
          key={groupEditorEntry.actorId}
          entry={groupEditorEntry}
          restoreFocusRef={groupEditorFocusRef}
          fallbackFocusRef={fallbackActionFocusRef}
          onOpenChange={(open) => {
            if (!open) closeGroupEditor();
          }}
          onSaved={async () => {
            await onGroupsSaved();
          }}
        />
      ) : null}
    </>
  );
}

function StaticColumnHeader({ children }: { children: string }) {
  return (
    <th scope="col" className="h-11 px-4 font-secondary-action text-content-secondary">
      {children}
    </th>
  );
}

type SortField = "NAME" | "EMAIL" | "STATUS";

function UsersSortButton({
  field,
  label,
  sort,
  onSortChange,
}: {
  field: SortField;
  label: string;
  sort: UsersSort;
  onSortChange: (sort: UsersSort) => void;
}) {
  const ascending = `${field}_ASC` as UsersSort;
  const descending = `${field}_DESC` as UsersSort;
  const direction = sort === ascending ? "asc" : sort === descending ? "desc" : undefined;
  return (
    <button
      type="button"
      aria-label={`Sort by ${label.toLowerCase()}`}
      onClick={() => onSortChange(sort === ascending ? descending : ascending)}
      className="inline-flex h-8 items-center gap-1 rounded-md px-1 font-secondary-action text-content-secondary outline-none transition-colors hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
    >
      {label}
      {direction === "asc" ? (
        <ArrowUp className="size-3.5" aria-hidden="true" />
      ) : direction === "desc" ? (
        <ArrowDown className="size-3.5" aria-hidden="true" />
      ) : (
        <ArrowUpDown className="size-3.5 opacity-35" aria-hidden="true" />
      )}
    </button>
  );
}

function userLabel(entry: UserListItem) {
  return (
    entry.displayName?.trim() ||
    entry.email?.trim() ||
    (entry.actorId ? `user ${entry.actorId}` : "this invitation")
  );
}

function UserIdentity({ entry }: { entry: UserListItem }) {
  const displayName = entry.displayName?.trim();
  const email = entry.email?.trim();
  const primary = displayName || email || "Name unavailable";
  const secondary = displayName
    ? email || "Email unavailable"
    : email
      ? entry.status === "INVITED"
        ? "Invited by email"
        : "Name unavailable"
      : "Email unavailable";
  return (
    <div className="min-w-0">
      <span className="flex min-w-0 items-center gap-2">
        <span className="truncate font-main-ui-action text-content-primary" title={primary}>
          {primary}
        </span>
        {entry.role === "OWNER" ? (
          <Badge variant="outline" className="shrink-0 bg-surface-raised text-content-secondary">
            Owner
          </Badge>
        ) : null}
      </span>
      <span
        className="mt-0.5 block truncate font-secondary-body text-content-muted"
        title={secondary}
      >
        {secondary}
        {entry.emailVerified === true ? <span className="sr-only">, verified email</span> : null}
      </span>
    </div>
  );
}

function UserStatus({
  entry,
  pendingAction,
}: {
  entry: UserListItem;
  pendingAction?: UserPendingAction;
}) {
  return (
    <div className="flex min-w-0 flex-col items-start gap-1">
      <StatusBadge tone={statusTone[entry.status]}>
        {entry.status === "ACTIVE"
          ? "Active"
          : entry.status === "INACTIVE"
            ? "Inactive"
            : "Invited"}
      </StatusBadge>
      {pendingAction ? (
        <span className="inline-flex items-center gap-1 font-secondary-body text-content-muted">
          <LoaderCircle
            className="size-3 animate-spin motion-reduce:animate-none"
            aria-hidden="true"
          />
          {userActionPendingLabel(pendingAction)}
        </span>
      ) : entry.status === "INVITED" && entry.invitationExpiresAt ? (
        <time
          dateTime={entry.invitationExpiresAt}
          title={new Date(entry.invitationExpiresAt).toLocaleString()}
          className="max-w-full font-secondary-body text-content-muted"
        >
          Expires {formatInvitationDate(entry.invitationExpiresAt)}
        </time>
      ) : null}
    </div>
  );
}

function columnAriaSort(sort: UsersSort, field: SortField, alternateField?: SortField) {
  const selectedField = sort.slice(0, sort.lastIndexOf("_")) as SortField;
  if (selectedField !== field && selectedField !== alternateField) return "none" as const;
  return sort.endsWith("_ASC") ? ("ascending" as const) : ("descending" as const);
}
