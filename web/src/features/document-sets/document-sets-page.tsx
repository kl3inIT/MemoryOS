import { useAppTranslation } from "@/i18n/use-app-translation";
import { useMemo, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createColumnHelper,
  rowPaginationFeature,
  tableFeatures,
  useTable,
  type PaginationState,
} from "@tanstack/react-table";
import { Link } from "@tanstack/react-router";
import { Library, Pencil, PlusCircle, Trash2 } from "lucide-react";
import { DataTable, type DataTableColumnMeta } from "@/components/data-table/data-table";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ClampedList } from "@/components/ui/clamped-list";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { TablePagination } from "@/components/ui/table-pagination";
import { actionErrorText } from "@/lib/action-errors";
import {
  documentSetOf,
  invalidateDocumentSets,
  type DocumentSet,
} from "@/features/document-sets/document-sets-api";
import { loadPersonaSources } from "@/features/chat/chat-personas-api";
import {
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import {
  deleteDocumentSetMutation,
  listDocumentSetsOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { DocumentSetAccessBadge } from "./document-set-access-badge";

const pageSize = 50;
/** Rows of Source chips a table cell shows before the rest move behind "+N". */
const visibleRows = 2;
const features = tableFeatures({ rowPaginationFeature, columnMeta: {} as DataTableColumnMeta });
const column = createColumnHelper<typeof features, DocumentSet>();

/*
 * Columns live at module scope: a cell renderer is a component, so a column list rebuilt during render
 * would remount every cell and drop its state, such as an open delete confirmation.
 */
const columns = column.columns([
  column.accessor("name", {
    header: function NameHeader() {
      const ui = useAppTranslation();
      return ui("Tên");
    },
    cell: ({ row }) => (
      <>
        <p className="font-main-ui-action break-words text-content-primary">{row.original.name}</p>
        {row.original.description ? (
          <p className="mt-0.5 line-clamp-2 font-secondary-body break-words text-content-muted">
            {row.original.description}
          </p>
        ) : null}
      </>
    ),
  }),
  column.accessor("sources", {
    header: function SourcesHeader() {
      const ui = useAppTranslation();
      return ui("Nguồn");
    },
    meta: { width: "w-104" },
    cell: ({ row }) => (
      <SourceList sources={row.original.sources} hidden={row.original.hiddenSources} />
    ),
  }),
  column.display({
    id: "access",
    header: function AccessHeader() {
      const ui = useAppTranslation();
      return ui("Quyền truy cập");
    },
    meta: { width: "w-44" },
    cell: ({ row }) => <DocumentSetAccessBadge set={row.original} />,
  }),
  column.display({
    id: "actions",
    header: function ActionsHeader() {
      const ui = useAppTranslation();
      return ui("Thao tác");
    },
    meta: { width: "w-24", align: "end" },
    cell: ({ row }) => <DocumentSetActions set={row.original} />,
  }),
]);

/** Onyx `/admin/documents/sets`: Document Sets are named, shareable Source allowlists that never grant access. */
export function DocumentSetsPage() {
  const ui = useAppTranslation();
  const canCreate = useGlobalCapability("AGENTS_CREATE");
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize });
  // The API pages by offset and sends no total, so one extra row tells whether a next page exists.
  const sets = useQuery({
    ...listDocumentSetsOptions({
      query: { offset: pagination.pageIndex * pagination.pageSize, limit: pagination.pageSize + 1 },
    }),
    select: (views) => views.map(documentSetOf),
    placeholderData: keepPreviousData,
  });

  return (
    <SettingsLayout wide className="gap-6">
      <div className="border-b border-border-subtle pb-5">
        <PageHeader title={ui("Bộ tài liệu")} icon={<Library />} iconSize="lg" />
      </div>
      <p className="max-w-3xl font-main-ui-body text-content-secondary">
        {ui(
          "Bộ tài liệu gom các nguồn có liên quan thành một nhóm. Dùng bộ tài liệu làm bộ lọc khi tìm kiếm hoặc gắn vào trợ lý để giới hạn phạm vi tìm. Bộ tài liệu không cấp thêm quyền đọc nguồn hay tài liệu.",
        )}
      </p>
      <div>
        {canCreate ? (
          <Button asChild prominence="secondary">
            <Link to="/admin/document-sets/new">
              <PlusCircle aria-hidden="true" />
              {ui("Bộ tài liệu mới")}
            </Link>
          </Button>
        ) : (
          <Button prominence="secondary" disabled>
            <PlusCircle aria-hidden="true" />
            {ui("Bộ tài liệu mới")}
          </Button>
        )}
      </div>
      {sets.isPending ? (
        <p role="status">{ui("Đang tải bộ tài liệu…")}</p>
      ) : sets.isError ? (
        <p role="alert" className="text-status-danger-content">
          {ui("Không tải được bộ tài liệu.")}{" "}
          <Button size="sm" prominence="tertiary" onClick={() => void sets.refetch()}>
            {ui("Tải lại")}
          </Button>
        </p>
      ) : sets.data.length > 0 || pagination.pageIndex > 0 ? (
        <DocumentSetTable
          rows={sets.data}
          pagination={pagination}
          paging={sets.isPlaceholderData}
          onPaginationChange={setPagination}
        />
      ) : null}
    </SettingsLayout>
  );
}

function DocumentSetTable({
  rows,
  pagination,
  paging,
  onPaginationChange,
}: {
  /** The current page and, when a next page exists, its first row. */
  rows: DocumentSet[];
  pagination: PaginationState;
  /** The rows shown belong to the page being replaced. */
  paging: boolean;
  onPaginationChange: (next: PaginationState) => void;
}) {
  const ui = useAppTranslation();
  const hasNextPage = rows.length > pagination.pageSize;
  const page = useMemo(() => rows.slice(0, pagination.pageSize), [rows, pagination.pageSize]);
  const table = useTable({
    features,
    columns,
    data: page,
    getRowId: (set) => set.id,
    manualPagination: true,
    pageCount: pagination.pageIndex + (hasNextPage ? 2 : 1),
    state: { pagination },
    onPaginationChange: (updater) =>
      onPaginationChange(typeof updater === "function" ? updater(pagination) : updater),
  });

  return (
    <section className="flex flex-col gap-3 border-t border-border-subtle pt-6">
      <h2 className="font-heading-h3 text-content-primary">{ui("Bộ tài liệu hiện có")}</h2>
      <DataTable
        table={table}
        label={ui("Bảng bộ tài liệu")}
        className="min-w-224"
        footer={
          table.getCanPreviousPage() || table.getCanNextPage() ? (
            <TablePagination
              label={ui("Trang bộ tài liệu")}
              page={pagination.pageIndex}
              totalPages={undefined}
              previousDisabled={paging || !table.getCanPreviousPage()}
              nextDisabled={paging || !table.getCanNextPage()}
              onPrevious={() => table.previousPage()}
              onNext={() => table.nextPage()}
            />
          ) : null
        }
      />
    </section>
  );
}

function DocumentSetActions({ set }: { set: DocumentSet }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const remove = useMutation({
    ...deleteDocumentSetMutation(),
    onSettled: () => invalidateDocumentSets(cache, set.id),
  });
  return (
    <div className="flex items-center justify-end gap-1">
      {set.permissions.edit && (
        <IconButton
          asChild
          prominence="internal"
          size="sm"
          aria-label={ui("Sửa {{v1}}", { v1: set.name })}
        >
          <Link to="/admin/document-sets/$documentSetId" params={{ documentSetId: set.id }}>
            <Pencil />
          </Link>
        </IconButton>
      )}
      {set.permissions.delete ? (
        <ConfirmDialog
          trigger={
            <IconButton
              prominence="internal"
              size="sm"
              aria-label={ui("Xóa {{v1}}", { v1: set.name })}
            >
              <Trash2 />
            </IconButton>
          }
          title={ui("Xóa {{v1}}?", { v1: set.name })}
          description={ui(
            "Bộ tài liệu sẽ bị gỡ khỏi mọi trợ lý đang dùng nó. Nguồn và tài liệu không bị xóa.",
          )}
          confirmLabel={ui("Xóa bộ tài liệu")}
          pendingLabel={ui("Đang xóa…")}
          confirmTone="danger"
          errorMessage={(cause) => actionErrorText(cause)}
          onConfirm={async () => {
            await remove.mutateAsync({
              path: { documentSetId: set.id },
              query: { revision: set.revision },
            });
          }}
        />
      ) : null}
    </div>
  );
}

function SourceList({
  sources,
  hidden,
}: {
  sources: { id: string; name: string }[];
  /** Sources of the set that this viewer may not select; only their count is known. */
  hidden: number;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  // Source types come from the Sources the actor can select; the set view carries only names.
  const selectable = useQuery({
    queryKey: ["chat-persona-sources", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
  const typeOf = (id: string) => selectable.data?.find((source) => source.id === id)?.type ?? "";
  return (
    <div className="flex flex-col gap-1.5">
      <ClampedList
        maxRows={visibleRows}
        label={ui("Nguồn")}
        items={sources.map((source) => {
          const Icon = findSourceProvider(typeOf(source.id))?.icon ?? Library;
          return (
            <span
              key={source.id}
              className="flex min-w-0 max-w-full items-center gap-1.5 rounded-full border border-border-subtle bg-surface-raised px-2.5 py-1 font-secondary-body text-content-secondary"
            >
              <Icon aria-hidden="true" className="size-3.5 shrink-0" />
              <span className="truncate" title={source.name || ui("Nguồn không còn khả dụng")}>
                {source.name || ui("Nguồn không còn khả dụng")}
              </span>
            </span>
          );
        })}
      />
      {hidden > 0 && (
        <p className="font-secondary-body text-content-muted">
          {ui("+{{n}} nguồn bạn không có quyền đọc", { n: hidden })}
        </p>
      )}
    </div>
  );
}
