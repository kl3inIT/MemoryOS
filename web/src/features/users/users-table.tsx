import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useProblemMessage } from "@/lib/use-problem-message";
import type { ErrorMessage } from "@/lib/problem-presentation";
import {
  createColumnHelper,
  rowPaginationFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type SortingState,
  type Table,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ArrowUpDown, User } from "lucide-react";
import { useRef, useState, type RefObject } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { Badge } from "@/components/ui/badge";
import { Field, FieldLabel } from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { StatusBadge, type StatusTone } from "@/components/ui/status-badge";
import { TablePagination } from "@/components/ui/table-pagination";
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
  rowErrors: Readonly<Partial<Record<string, ErrorMessage>>>;
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

/** What the row cells need from the page: progress, failures and the row actions. */
type UsersTableMeta = Pick<
  UsersTableProps,
  | "pendingActions"
  | "rowErrors"
  | "invitationPending"
  | "canEditGroups"
  | "fallbackActionFocusRef"
  | "onActivate"
  | "onDeactivate"
  | "onRotate"
  | "onRevoke"
> & {
  /** A membership change can move the row out of the current view. */
  membershipChangesView: boolean;
  openGroupEditor: (entry: UserListItem, returnTarget: HTMLElement | null) => void;
};

const statusTone: Record<UserListItem["status"], StatusTone> = {
  ACTIVE: "success",
  INACTIVE: "neutral",
  INVITED: "warning",
};

const pageSizes = [20, 50, 100] as const;

const features = tableFeatures({
  rowPaginationFeature,
  rowSortingFeature,
  columnMeta: {} as DataTableColumnMeta,
  tableMeta: {} as UsersTableMeta,
});
const column = createColumnHelper<typeof features, UserListItem>();
type UsersTableInstance = Table<typeof features, UserListItem>;

/** The API sorts; the table's sorting state names the sorted field and direction. */
type SortField = "name" | "email" | "status";

function sortingOf(sort: UsersSort): SortingState {
  const field = sort.slice(0, sort.lastIndexOf("_")).toLowerCase();
  return [{ id: field, desc: sort.endsWith("_DESC") }];
}

function sortOf([first]: SortingState): UsersSort | undefined {
  return first
    ? (`${first.id.toUpperCase()}_${first.desc ? "DESC" : "ASC"}` as UsersSort)
    : undefined;
}

function rowKey(entry: UserListItem) {
  return entry.actorId ? `actor:${entry.actorId}` : `invitation:${entry.invitationId}`;
}

function useRowLabel() {
  const ui = useAppTranslation();
  return (entry: UserListItem) =>
    entry.displayName?.trim() ||
    entry.email?.trim() ||
    (entry.actorId ? ui("user {{id}}", { id: entry.actorId }) : ui("this invitation"));
}

/*
 * Columns live at module scope, because a cell renderer is a component and a rebuilt list remounts
 * every cell, dropping an open row menu or confirmation.
 */
const columns = column.columns([
  column.display({
    id: "name",
    header: function NameHeader({ table }) {
      const ui = useAppTranslation();
      return (
        <span className="inline-flex items-center gap-0.5">
          <SortButton table={table} field="name" label={ui("Name")} />
          <span className="font-secondary-action text-content-muted" aria-hidden="true">
            /
          </span>
          <SortButton table={table} field="email" label={ui("Email")} />
        </span>
      );
    },
    cell: ({ row }) => <UserIdentity entry={row.original} />,
  }),
  column.display({
    id: "groups",
    header: function GroupsHeader() {
      const ui = useAppTranslation();
      return ui("Groups");
    },
    meta: { width: "w-3/10" },
    cell: function GroupsCell({ row, table }) {
      const label = useRowLabel();
      const meta = table.options.meta!;
      const entry = row.original;
      return (
        <GroupTags
          groups={entry.groups}
          editable={meta.canEditGroups && Boolean(entry.actorId)}
          userLabel={label(entry)}
          onEdit={(target) => meta.openGroupEditor(entry, target)}
        />
      );
    },
  }),
  column.display({
    id: "accountType",
    header: function AccountTypeHeader() {
      const ui = useAppTranslation();
      return ui("Account type");
    },
    meta: { width: "w-40" },
    cell: function AccountTypeCell({ row }) {
      const ui = useAppTranslation();
      return row.original.accountType === "STANDARD" ? (
        <span className="inline-flex items-center gap-1.5 font-main-ui-body text-content-secondary">
          <User className="size-4 text-content-muted" aria-hidden="true" />
          {ui("Standard")}
        </span>
      ) : (
        <span
          aria-label={ui("Account type assigned after invitation acceptance")}
          className="text-content-muted"
        >
          —
        </span>
      );
    },
  }),
  column.display({
    id: "status",
    header: function StatusHeader({ table }) {
      const ui = useAppTranslation();
      return <SortButton table={table} field="status" label={ui("Status")} />;
    },
    meta: { width: "w-48" },
    cell: ({ row, table }) => {
      const meta = table.options.meta!;
      const key = rowKey(row.original);
      return (
        <UserStatus
          entry={row.original}
          pendingAction={meta.pendingActions[key]}
          error={meta.rowErrors[key]}
        />
      );
    },
  }),
  column.display({
    id: "actions",
    header: function ActionsHeader() {
      const ui = useAppTranslation();
      return <span className="sr-only">{ui("Actions")}</span>;
    },
    meta: { width: "w-16", align: "end" },
    cell: ({ row, table }) => {
      const meta = table.options.meta!;
      const entry = row.original;
      return (
        <UserRowActions
          entry={entry}
          pendingAction={meta.pendingActions[rowKey(entry)]}
          invitationPending={meta.invitationPending}
          membershipChangesView={meta.membershipChangesView}
          canEditGroups={meta.canEditGroups}
          fallbackFocusRef={meta.fallbackActionFocusRef}
          onEditGroups={(target) => meta.openGroupEditor(entry, target)}
          onActivate={meta.onActivate}
          onDeactivate={meta.onDeactivate}
          onRotate={meta.onRotate}
          onRevoke={meta.onRevoke}
        />
      );
    },
  }),
]);

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
  const ui = useAppTranslation();
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

  const sorting = sortingOf(sort);
  const pagination = { pageIndex: page, pageSize: size };
  const table = useTable({
    features,
    columns,
    data: entries,
    getRowId: rowKey,
    meta: {
      pendingActions,
      rowErrors,
      invitationPending,
      canEditGroups,
      fallbackActionFocusRef,
      membershipChangesView: Boolean(statusFilter) || sort.startsWith("STATUS_"),
      openGroupEditor,
      onActivate,
      onDeactivate,
      onRotate,
      onRevoke,
    },
    manualSorting: true,
    enableMultiSort: false,
    enableSortingRemoval: false,
    manualPagination: true,
    pageCount: totalPages,
    state: { sorting, pagination },
    onSortingChange: (updater) => {
      const next = sortOf(typeof updater === "function" ? updater(sorting) : updater);
      if (next) onSortChange(next);
    },
    onPaginationChange: (updater) =>
      onPageChange((typeof updater === "function" ? updater(pagination) : updater).pageIndex),
  });

  return (
    <>
      <DataTable
        table={table}
        label={ui("Tenant users")}
        className="min-w-224"
        footer={
          <TablePagination
            label={ui("User pages")}
            page={page}
            totalPages={totalPages}
            summary={ui("Showing {{first}}–{{last}} of {{total}}", {
              first: firstItem,
              last: lastItem,
              total: totalItems,
            })}
            previousDisabled={!table.getCanPreviousPage()}
            nextDisabled={!table.getCanNextPage()}
            onPrevious={() => table.previousPage()}
            onNext={() => table.nextPage()}
          >
            <Field orientation="horizontal" className="w-auto">
              <FieldLabel htmlFor="users-page-size">{ui("Rows")}</FieldLabel>
              <NativeSelect
                id="users-page-size"
                aria-label={ui("Rows per page")}
                value={size}
                size="sm"
                className="w-auto px-2"
                onChange={(event) =>
                  onSizeChange(Number(event.target.value) as UsersSearch["size"])
                }
              >
                {pageSizes.map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          </TablePagination>
        }
      />

      {groupEditorEntry ? (
        <UserGroupsDialog
          key={groupEditorEntry.actorId}
          entry={groupEditorEntry}
          restoreFocusRef={groupEditorFocusRef}
          fallbackFocusRef={fallbackActionFocusRef}
          onOpenChange={(open) => {
            if (!open) closeGroupEditor();
          }}
          onSaved={onGroupsSaved}
        />
      ) : null}
    </>
  );
}

/** Sorts the users by one field; a second press reverses the direction. */
function SortButton({
  table,
  field,
  label,
}: {
  table: UsersTableInstance;
  field: SortField;
  label: string;
}) {
  const ui = useAppTranslation();
  const current = table.options.state?.sorting?.[0];
  const direction = current?.id === field ? (current.desc ? "desc" : "asc") : undefined;
  return (
    <button
      type="button"
      aria-label={ui("Sort by {{v1}}", { v1: label.toLowerCase() })}
      onClick={() => table.setSorting([{ id: field, desc: direction === "asc" }])}
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

function UserIdentity({ entry }: { entry: UserListItem }) {
  const ui = useAppTranslation();

  const displayName = entry.displayName?.trim();
  const email = entry.email?.trim();
  const primary = displayName || email || ui("Name unavailable");
  const secondary = displayName
    ? email || ui("Email unavailable")
    : email
      ? entry.status === "INVITED"
        ? ui("Invited by email")
        : ui("Name unavailable")
      : ui("Email unavailable");
  return (
    <div className="min-w-0">
      <span className="flex min-w-0 items-center gap-2">
        <span className="truncate font-main-ui-action text-content-primary" title={primary}>
          {primary}
        </span>
        {entry.role === "OWNER" ? (
          <Badge variant="outline" className="shrink-0">
            {ui("Owner")}
          </Badge>
        ) : null}
      </span>
      <span
        className="mt-0.5 block truncate font-secondary-body text-content-muted"
        title={secondary}
      >
        {secondary}
        {entry.emailVerified === true ? (
          <span className="sr-only">{ui(", verified email")}</span>
        ) : null}
      </span>
    </div>
  );
}

function UserStatus({
  entry,
  pendingAction,
  error,
}: {
  entry: UserListItem;
  pendingAction?: UserPendingAction;
  /** A failed action that has no dialog of its own to show its error, such as a link rotation. */
  error?: ErrorMessage;
}) {
  const ui = useAppTranslation();
  const errorMessage = useProblemMessage();

  return (
    <div className="flex min-w-0 flex-col items-start gap-1">
      <StatusBadge tone={statusTone[entry.status]}>
        {entry.status === "ACTIVE"
          ? ui("Active")
          : entry.status === "INACTIVE"
            ? ui("Inactive")
            : ui("Invited")}
      </StatusBadge>
      {pendingAction ? (
        <span className="inline-flex items-center gap-1 font-secondary-body text-content-muted">
          <Spinner aria-hidden="true" />
          {ui(userActionPendingLabel(pendingAction))}
        </span>
      ) : entry.status === "INVITED" && entry.invitationExpiresAt ? (
        <time
          dateTime={entry.invitationExpiresAt}
          title={new Date(entry.invitationExpiresAt).toLocaleString(uiLocale())}
          className="max-w-full font-secondary-body text-content-muted"
        >
          {ui("Expires")} {formatInvitationDate(entry.invitationExpiresAt)}
        </time>
      ) : null}
      {error ? (
        <p role="alert" className="font-secondary-body text-status-danger-content">
          {errorMessage(error)}
        </p>
      ) : null}
    </div>
  );
}
