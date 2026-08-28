import {
  createColumnHelper,
  rowPaginationFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type PaginationState,
  type SortingState,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, RefreshCw, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Select } from "@/components/ui/select";
import { TextButton } from "@/components/ui/text-button";
import type {
  InvitationListSearch,
  InvitationSort,
} from "@/features/invitations/invitation-list-search";
import { formatInvitationDate } from "@/features/invitations/invitation-presentation";
import type { Invitation } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";

const invitationTableFeatures = tableFeatures({
  rowPaginationFeature,
  rowSortingFeature,
});
const columnHelper = createColumnHelper<typeof invitationTableFeatures, Invitation>();

const statusStyles: Record<Invitation["status"], string> = {
  PENDING: "bg-status-warning-surface text-status-warning-content",
  ACCEPTED: "bg-status-success-surface text-status-success-content",
  EXPIRED: "bg-status-info-surface text-status-info-content",
  REVOKED: "bg-status-info-surface text-content-muted",
};

export type InvitationPendingAction = "rotate" | "revoke";

type InvitationTableMeta = {
  pendingActions: Readonly<Partial<Record<string, InvitationPendingAction>>>;
  rowErrors: Readonly<Partial<Record<string, string>>>;
  onRotate: (invitation: Invitation) => void;
  onRevoke: (invitation: Invitation) => void;
};

const columns = columnHelper.columns([
  columnHelper.accessor("email", {
    header: "Email",
    cell: ({ getValue }) => {
      const email = getValue();
      return (
        <span
          className="block max-w-72 truncate font-main-ui-action text-content-primary"
          title={email}
        >
          {email}
        </span>
      );
    },
  }),
  columnHelper.display({
    id: "status",
    header: "Status",
    enableSorting: false,
    cell: ({ row }) => (
      <span
        className={cn(
          "inline-flex rounded-full px-2 py-0.5 font-figure-small-label tracking-wide",
          statusStyles[row.original.status],
        )}
      >
        {statusLabel(row.original.status)}
      </span>
    ),
  }),
  columnHelper.accessor("createdAt", {
    header: "Created",
    sortDescFirst: true,
    cell: ({ getValue }) => {
      const createdAt = getValue();
      return (
        <time
          dateTime={createdAt}
          className="font-secondary-body whitespace-nowrap text-content-secondary"
        >
          {formatInvitationDate(createdAt)}
        </time>
      );
    },
  }),
  columnHelper.display({
    id: "lifecycle",
    header: "Lifecycle",
    enableSorting: false,
    cell: ({ row }) => (
      <span className="font-secondary-body whitespace-nowrap text-content-muted">
        {lifecycleDate(row.original)}
      </span>
    ),
  }),
  columnHelper.display({
    id: "actions",
    header: "Actions",
    enableSorting: false,
    cell: ({ row, table }) => {
      const meta = table.options.meta as InvitationTableMeta;
      return (
        <InvitationRowActions
          invitation={row.original}
          pendingAction={meta.pendingActions[row.id]}
          error={meta.rowErrors[row.id]}
          onRotate={meta.onRotate}
          onRevoke={meta.onRevoke}
        />
      );
    },
  }),
]);

type InvitationTableProps = {
  invitations: Invitation[];
  sort: InvitationSort;
  page: number;
  size: InvitationListSearch["size"];
  totalItems: number;
  pendingActions: Readonly<Partial<Record<string, InvitationPendingAction>>>;
  rowErrors: Readonly<Partial<Record<string, string>>>;
  onSortChange: (sort: InvitationSort) => void;
  onPageChange: (page: number) => void;
  onSizeChange: (size: InvitationListSearch["size"]) => void;
  onRotate: (invitation: Invitation) => void;
  onRevoke: (invitation: Invitation) => void;
};

export function InvitationTable({
  invitations,
  sort,
  page,
  size,
  totalItems,
  pendingActions,
  rowErrors,
  onSortChange,
  onPageChange,
  onSizeChange,
  onRotate,
  onRevoke,
}: InvitationTableProps) {
  const sorting = invitationSortingState(sort);
  const pagination: PaginationState = { pageIndex: page, pageSize: size };
  const table = useTable({
    features: invitationTableFeatures,
    columns,
    data: invitations,
    meta: { pendingActions, rowErrors, onRotate, onRevoke },
    getRowId: (invitation) => invitation.id,
    rowCount: totalItems,
    manualPagination: true,
    manualSorting: true,
    enableSortingRemoval: false,
    state: { sorting, pagination },
    onSortingChange: (updater) => {
      const nextSorting = typeof updater === "function" ? updater(sorting) : updater;
      onSortChange(invitationSort(nextSorting));
    },
    onPaginationChange: (updater) => {
      const nextPagination = typeof updater === "function" ? updater(pagination) : updater;
      if (nextPagination.pageSize !== size) {
        onSizeChange(nextPagination.pageSize as InvitationListSearch["size"]);
        return;
      }
      onPageChange(nextPagination.pageIndex);
    },
  });

  const firstItem = totalItems === 0 ? 0 : page * size + 1;
  const lastItem = Math.min((page + 1) * size, totalItems);

  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[48rem] border-collapse">
          <caption className="sr-only">Organization invitations</caption>
          <thead className="border-b border-border-subtle bg-surface-subtle/40 text-left">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map((header) => {
                  const sorted = header.column.getIsSorted();
                  const sortable = header.column.getCanSort();
                  return (
                    <th
                      key={header.id}
                      scope="col"
                      aria-sort={
                        sorted === "asc" ? "ascending" : sorted === "desc" ? "descending" : "none"
                      }
                      className={cn("px-4 py-3", header.column.id === "actions" && "text-right")}
                    >
                      {header.isPlaceholder ? null : sortable ? (
                        <TextButton
                          size="sm"
                          className="gap-1.5 font-secondary-action"
                          onClick={header.column.getToggleSortingHandler()}
                        >
                          <table.FlexRender header={header} />
                          {sorted === "asc" ? (
                            <ArrowUp className="size-3.5" aria-hidden="true" />
                          ) : (
                            <ArrowDown
                              className={cn("size-3.5", sorted !== "desc" && "opacity-35")}
                              aria-hidden="true"
                            />
                          )}
                        </TextButton>
                      ) : header.column.id === "actions" ? (
                        <span className="sr-only">Actions</span>
                      ) : (
                        <span className="font-secondary-action text-content-secondary">
                          <table.FlexRender header={header} />
                        </span>
                      )}
                    </th>
                  );
                })}
              </tr>
            ))}
          </thead>
          <tbody className="divide-y divide-border-subtle">
            {table.getRowModel().rows.map((row) => (
              <tr key={row.id} className="align-top">
                {row.getAllCells().map((cell) => (
                  <td key={cell.id} className="px-4 py-4">
                    <table.FlexRender cell={cell} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <nav
        aria-label="Invitation pages"
        className="flex flex-col gap-3 border-t border-border-subtle px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
      >
        <p className="font-secondary-body text-content-muted">
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
              onChange={(event) => table.setPageSize(Number(event.target.value))}
            >
              <option value={20}>20</option>
              <option value={50}>50</option>
              <option value={100}>100</option>
            </Select>
          </label>
          <span className="min-w-24 text-center font-secondary-body text-content-secondary">
            Page {page + 1} of {Math.max(table.getPageCount(), 1)}
          </span>
          <Button
            type="button"
            size="sm"
            prominence="secondary"
            disabled={!table.getCanPreviousPage()}
            onClick={() => table.previousPage()}
          >
            Previous
          </Button>
          <Button
            type="button"
            size="sm"
            prominence="secondary"
            disabled={!table.getCanNextPage()}
            onClick={() => table.nextPage()}
          >
            Next
          </Button>
        </div>
      </nav>
    </>
  );
}

type InvitationRowActionsProps = {
  invitation: Invitation;
  pendingAction?: InvitationPendingAction;
  error?: string;
  onRotate: (invitation: Invitation) => void;
  onRevoke: (invitation: Invitation) => void;
};

function InvitationRowActions({
  invitation,
  pendingAction,
  error,
  onRotate,
  onRevoke,
}: InvitationRowActionsProps) {
  if (invitation.status !== "PENDING") return null;

  return (
    <div className="flex min-w-52 flex-col items-end gap-1.5">
      <div className="flex gap-1">
        <Button
          prominence="secondary"
          size="sm"
          aria-label={`Rotate invitation link for ${invitation.email}`}
          disabled={pendingAction !== undefined}
          onClick={() => onRotate(invitation)}
        >
          <RefreshCw />
          {pendingAction === "rotate" ? "Rotating…" : "Rotate"}
        </Button>
        <Button
          tone="danger"
          prominence="tertiary"
          size="sm"
          aria-label={`Revoke invitation for ${invitation.email}`}
          disabled={pendingAction !== undefined}
          onClick={() => onRevoke(invitation)}
        >
          <Trash2 />
          {pendingAction === "revoke" ? "Revoking…" : "Revoke"}
        </Button>
      </div>
      {error && (
        <p
          role="alert"
          className="max-w-64 text-right font-secondary-body text-status-danger-content"
        >
          {error}
        </p>
      )}
    </div>
  );
}

function invitationSortingState(sort: InvitationSort): SortingState {
  return sort.startsWith("EMAIL")
    ? [{ id: "email", desc: sort === "EMAIL_DESC" }]
    : [{ id: "createdAt", desc: sort === "CREATED_AT_DESC" }];
}

function invitationSort(sorting: SortingState): InvitationSort {
  const activeSort = sorting[0];
  if (!activeSort) return "CREATED_AT_DESC";
  if (activeSort.id === "email") return activeSort.desc ? "EMAIL_DESC" : "EMAIL_ASC";
  return activeSort.desc ? "CREATED_AT_DESC" : "CREATED_AT_ASC";
}

function lifecycleDate(invitation: Invitation) {
  if (invitation.status === "ACCEPTED" && invitation.acceptedAt) {
    return (
      <time dateTime={invitation.acceptedAt}>
        Joined {formatInvitationDate(invitation.acceptedAt)}
      </time>
    );
  }
  if (invitation.status === "REVOKED" && invitation.revokedAt) {
    return (
      <time dateTime={invitation.revokedAt}>
        Revoked {formatInvitationDate(invitation.revokedAt)}
      </time>
    );
  }
  return (
    <time dateTime={invitation.expiresAt}>
      Expires {formatInvitationDate(invitation.expiresAt)}
    </time>
  );
}

function statusLabel(status: Invitation["status"]) {
  return status.charAt(0) + status.slice(1).toLowerCase();
}
