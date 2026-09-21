import { useAppTranslation } from "@/i18n/use-app-translation";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import { Library, Pencil, PlusCircle, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ClampedList } from "@/components/ui/clamped-list";
import { IconButton } from "@/components/ui/icon-button";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { TablePagination } from "@/components/ui/table-pagination";
import { sameOriginMutationHeaders } from "@/lib/api";
import { chatActionError } from "@/features/chat/chat-action-utils";
import {
  documentSetsKey,
  loadDocumentSets,
  loadPersonaSources,
  type DocumentSet,
} from "@/features/chat/chat-workspace-api";
import {
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { findSourceProvider } from "@/features/sources/source-provider-catalog";
import { deleteDocumentSet } from "@/lib/hey-api/sdk.gen";
import { DocumentSetAccessBadge } from "./document-set-access-badge";

const pageSize = 50;
/** Rows of Source chips a table cell shows before the rest move behind "+N". */
const visibleRows = 2;

/** Onyx `/admin/documents/sets`: Document Sets are named, shareable Source allowlists that never grant access. */
export function DocumentSetsPage() {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const canCreate = useGlobalCapability("AGENTS_CREATE");
  const sets = useQuery({
    queryKey: [...documentSetsKey, actorId, authorizationVersion],
    queryFn: ({ signal }) => loadDocumentSets(signal),
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
      ) : sets.data.length > 0 ? (
        <DocumentSetTable sets={sets.data} />
      ) : null}
    </SettingsLayout>
  );
}

function DocumentSetTable({ sets }: { sets: DocumentSet[] }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [page, setPage] = useState(0);
  // Source types come from the Sources the actor can select; the set view carries only names.
  const sources = useQuery({
    queryKey: ["chat-persona-sources", actorId, authorizationVersion],
    queryFn: ({ signal }) => loadPersonaSources(signal),
  });
  const sourceTypes = new Map(sources.data?.map((source) => [source.id, source.type]));
  const sorted = [...sets].sort(
    (a, b) =>
      Number(b.permissions.edit) - Number(a.permissions.edit) || a.name.localeCompare(b.name),
  );
  const totalPages = Math.ceil(sorted.length / pageSize);
  const currentPage = Math.min(page, totalPages - 1);

  return (
    <section className="flex flex-col gap-3 border-t border-border-subtle pt-6">
      <h2 className="font-heading-h3 text-content-primary">{ui("Bộ tài liệu hiện có")}</h2>
      <div
        className="relative overflow-x-auto"
        tabIndex={0}
        role="region"
        aria-label={ui("Bảng bộ tài liệu")}
      >
        <Table className="w-full min-w-[56rem] table-fixed border-collapse">
          <colgroup>
            <col />
            <col className="w-[26rem]" />
            <col className="w-44" />
            <col className="w-24" />
          </colgroup>
          <TableHeader>
            <TableRow className="border-x border-border-subtle bg-surface-raised">
              <TableHead className="px-4 whitespace-nowrap">{ui("Tên")}</TableHead>
              <TableHead className="px-4 whitespace-nowrap">{ui("Nguồn")}</TableHead>
              <TableHead className="px-4 whitespace-nowrap">{ui("Quyền truy cập")}</TableHead>
              <TableHead className="px-4 text-right whitespace-nowrap">{ui("Thao tác")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody className="[&_tr:last-child]:border-x [&_tr:last-child]:border-b">
            {sorted.slice(currentPage * pageSize, (currentPage + 1) * pageSize).map((set) => (
              <TableRow
                key={set.id}
                className="border-x border-b border-border-subtle hover:bg-surface-subtle/50"
              >
                <TableCell className="px-4 py-3 align-top">
                  <p className="font-main-ui-action break-words text-content-primary">{set.name}</p>
                  {set.description ? (
                    <p className="mt-0.5 line-clamp-2 font-secondary-body break-words text-content-muted">
                      {set.description}
                    </p>
                  ) : null}
                </TableCell>
                <TableCell className="px-4 py-3 align-top">
                  <SourceList
                    sources={set.sources.map((source) => ({
                      ...source,
                      type: sourceTypes.get(source.id) ?? "",
                    }))}
                    hidden={set.hiddenSources}
                  />
                </TableCell>
                <TableCell className="px-4 py-3 align-top">
                  <DocumentSetAccessBadge set={set} />
                </TableCell>
                <TableCell className="px-4 py-3 align-top">
                  <div className="flex items-center justify-end gap-1">
                    {set.permissions.edit && (
                      <IconButton
                        asChild
                        prominence="internal"
                        size="sm"
                        aria-label={ui("Sửa {{v1}}", { v1: set.name })}
                      >
                        <Link
                          to="/admin/document-sets/$documentSetId"
                          params={{ documentSetId: set.id }}
                        >
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
                        errorMessage={(cause) => chatActionError(cause)}
                        onConfirm={async () => {
                          await deleteDocumentSet({
                            path: { documentSetId: set.id },
                            query: { revision: set.revision },
                            headers: sameOriginMutationHeaders,
                            throwOnError: true,
                          });
                          await cache.invalidateQueries({ queryKey: documentSetsKey });
                        }}
                      />
                    ) : null}
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      {totalPages > 1 && (
        <TablePagination
          label={ui("Trang bộ tài liệu")}
          className="px-0"
          page={currentPage}
          totalPages={totalPages}
          previousDisabled={currentPage === 0}
          nextDisabled={currentPage + 1 >= totalPages}
          onPrevious={() => setPage(currentPage - 1)}
          onNext={() => setPage(currentPage + 1)}
        />
      )}
    </section>
  );
}

function SourceList({
  sources,
  hidden,
}: {
  sources: { id: string; name: string; type: string }[];
  /** Sources of the set that this viewer may not select; only their count is known. */
  hidden: number;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-col gap-1.5">
      <ClampedList
        maxRows={visibleRows}
        label={ui("Nguồn")}
        items={sources.map((source) => {
          const Icon = findSourceProvider(source.type)?.icon ?? Library;
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
