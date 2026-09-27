import { useAppTranslation } from "@/i18n/use-app-translation";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
  type PaginationState,
} from "@tanstack/react-table";
import { CircleMinus, ShieldUser } from "lucide-react";
import { useState, type RefObject } from "react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { TablePagination } from "@/components/ui/table-pagination";
import { listGroupMembersOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import type { GroupMember, GroupSummary } from "@/lib/hey-api/types.gen";
import { groupMutationError } from "./group-errors";
import {
  EmptyRows,
  InlineError,
  LoadingRows,
  MemberAccount,
  MemberIdentity,
  PageSummary,
} from "./group-member-list-parts";
import type { GroupMembersDraft } from "./group-members-draft";

const pageSize = 10;

type MemberTableMeta = {
  group: GroupSummary;
  draft: GroupMembersDraft;
  /** Where focus goes once a member leaves the list. */
  addButtonRef: RefObject<HTMLButtonElement | null>;
};

const features = tableFeatures({
  rowPaginationFeature,
  columnMeta: {} as DataTableColumnMeta,
  tableMeta: {} as MemberTableMeta,
});
const column = createColumnHelper<typeof features, GroupMember>();

/*
 * Columns live at module scope, because a cell renderer is a component and a rebuilt list remounts
 * every cell, closing an open confirmation.
 */
const columns = column.columns([
  column.display({
    id: "name",
    header: function NameHeader() {
      const ui = useAppTranslation();
      return ui("Name");
    },
    cell: ({ row }) => <MemberIdentity member={row.original} />,
  }),
  column.display({
    id: "accountType",
    header: function AccountTypeHeader() {
      const ui = useAppTranslation();
      return ui("Account Type");
    },
    meta: { width: "w-28 sm:w-40" },
    cell: ({ row }) => <MemberAccount member={row.original} />,
  }),
  column.display({
    id: "actions",
    header: function ActionsHeader() {
      const ui = useAppTranslation();
      return <span className="sr-only">{ui("Actions")}</span>;
    },
    meta: { width: "w-24", align: "end" },
    cell: ({ row, table }) => <MemberActions member={row.original} {...table.options.meta!} />,
  }),
]);

/** The Group's members as the draft shows them, a page at a time. */
export function GroupMemberTable({
  group,
  draft,
  search,
  addButtonRef,
}: {
  group: GroupSummary;
  draft: GroupMembersDraft;
  /** The settled search text. */
  search: string;
  addButtonRef: RefObject<HTMLButtonElement | null>;
}) {
  const ui = useAppTranslation();
  // A new search starts again at its first page.
  const [paged, setPaged] = useState({ search, pageIndex: 0 });
  const pageIndex = paged.search === search ? paged.pageIndex : 0;
  const members = useQuery({
    ...listGroupMembersOptions({
      path: { groupId: group.id },
      query: { search: search || undefined, page: pageIndex, size: pageSize },
    }),
    placeholderData: keepPreviousData,
    retry: false,
  });
  const totalPages = members.data?.totalPages;
  // Removals can leave the page past the last one; move back to the last page.
  if (
    !members.isPlaceholderData &&
    totalPages !== undefined &&
    pageIndex > Math.max(totalPages - 1, 0)
  )
    setPaged({ search, pageIndex: Math.max(totalPages - 1, 0) });

  const savedRows = (members.data?.items ?? []).flatMap((member) => {
    const row = draft.staged(member);
    return row ? [row] : [];
  });
  // Members the draft adds lead the first page until they are saved.
  const addedRows =
    pageIndex === 0
      ? [...draft.added.values()]
          .filter(
            (member) =>
              !search || member.email?.toLocaleLowerCase().includes(search.toLocaleLowerCase()),
          )
          .flatMap((member) => {
            const row = draft.staged(member);
            return row ? [row] : [];
          })
      : [];
  const rows = [...addedRows, ...savedRows];
  const pagination: PaginationState = { pageIndex, pageSize };
  const table = useTable({
    features,
    columns,
    data: rows,
    getRowId: (member) => member.actorId,
    meta: { group, draft, addButtonRef },
    manualPagination: true,
    pageCount: Math.max(totalPages ?? 1, 1),
    state: { pagination },
    onPaginationChange: (updater) =>
      setPaged({
        search,
        pageIndex: (typeof updater === "function" ? updater(pagination) : updater).pageIndex,
      }),
  });

  const footer = members.data ? (
    <TablePagination
      label={ui("Member pages")}
      page={pageIndex}
      totalPages={members.data.totalPages}
      summary={<PageSummary page={members.data} shown={rows.length} />}
      previousDisabled={members.isFetching || !table.getCanPreviousPage()}
      nextDisabled={members.isFetching || !table.getCanNextPage()}
      onPrevious={() => table.previousPage()}
      onNext={() => table.nextPage()}
    />
  ) : null;

  if (members.isPending) return <LoadingRows label={ui("Loading members")} />;
  if (members.isError)
    return (
      <InlineError
        label={ui("Members could not be loaded.")}
        onRetry={() => void members.refetch()}
      />
    );
  if (rows.length === 0)
    return (
      <>
        <EmptyRows
          title={search ? ui("No members found") : ui("No members")}
          detail={
            search
              ? ui("Try another name or email.")
              : ui("Add an eligible Tenant user to this group.")
          }
        />
        {footer}
      </>
    );
  return (
    <DataTable table={table} label={ui("Members of {{v1}}", { v1: group.name })} footer={footer} />
  );
}

function MemberActions({
  member,
  group,
  draft,
  addButtonRef,
}: MemberTableMeta & { member: GroupMember }) {
  const ui = useAppTranslation();
  const { canManageMembers, canManageManagers, currentActorId } = draft;
  const name = member.email?.trim() || ui("member without email");
  const ownManager = member.isManager && member.actorId === currentActorId;
  const locked = draft.pending || member.protectedOwner || ownManager;

  return (
    <div className="flex flex-wrap items-center justify-end gap-0.5">
      {canManageManagers ? (
        <ConfirmDialog
          trigger={
            <IconButton
              size="sm"
              disabled={locked}
              pending={draft.managerPendingFor(member.actorId)}
              aria-label={ui("{{v1}} for {{v2}}", {
                v1: ui(member.isManager ? "Remove manager" : "Make manager"),
                v2: name,
              })}
            >
              <ShieldUser />
            </IconButton>
          }
          title={ui(
            member.isManager ? "Remove manager access from {{name}}?" : "Make {{name}} a manager?",
            { name },
          )}
          description={
            member.isManager
              ? ui("Their group-scoped management access ends on the next authorized request.")
              : ui(
                  "They will be able to maintain this group’s ordinary membership and access associated Sources within their granted scope.",
                )
          }
          confirmLabel={member.isManager ? ui("Remove manager") : ui("Make manager")}
          pendingLabel={ui("Updating manager…")}
          confirmTone={member.isManager ? "danger" : "default"}
          onConfirm={async () => draft.toggleManager(member)}
          errorMessage={(cause) => groupMutationError(cause, "manager")}
        />
      ) : null}
      {canManageMembers && (!member.isManager || canManageManagers) ? (
        <ConfirmDialog
          trigger={
            <IconButton
              size="sm"
              prominence="tertiary"
              disabled={locked}
              aria-label={ui("Remove {{v1}} from {{v2}}", { v1: name, v2: group.name })}
            >
              <CircleMinus />
            </IconButton>
          }
          successFocusRef={addButtonRef}
          fallbackFocusRef={addButtonRef}
          title={ui("Remove {{v1}}?", { v1: name })}
          description={ui(
            "They will leave “{{v1}}”. Other group memberships and their Tenant account stay unchanged.",
            { v1: group.name },
          )}
          confirmLabel={ui("Remove member")}
          pendingLabel={ui("Removing member…")}
          onConfirm={async () => {
            draft.remove(member);
            addButtonRef.current?.focus();
          }}
          errorMessage={(cause) => groupMutationError(cause, "members")}
        />
      ) : null}
    </div>
  );
}
