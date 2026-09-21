import { useDeferredValue, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Trash2 } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { TablePagination } from "@/components/ui/table-pagination";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { chatActionError } from "./chat-action-utils";
import { ChatAddToProjectDialog } from "./chat-add-to-project";
import { ChatDialog } from "./chat-dialog";
import { LibraryContentMatches } from "./chat-library-content";
import { LibraryDropZone, LibraryUploadButton, LibraryUploadTray } from "./chat-library-uploads";
import { LibraryRail, type LibraryView } from "./chat-library-rail";
import { FileActions, LibraryEmpty, LibraryList } from "./chat-library-rows";
import {
  LibraryFilterPills,
  LibrarySelectionBar,
  LibraryToolbar,
  type LibraryLayout,
  type LibrarySearchMode,
} from "./chat-library-toolbar";
import { archiveContentUrl, useLibraryArchive } from "./use-library-archive";
import { useLibraryUploads } from "./use-library-uploads";
import { fileSize } from "./chat-code";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import { type PreviewTarget } from "./chat-file-preview";
import {
  changeLibraryFile,
  chatLibraryKey,
  deleteLibraryFile,
  emptyLibraryTrash,
  loadLibraryUsage,
  loadTrashWindow,
  purgeLibraryFile,
  restoreLibraryFile,
  libraryPreviewTarget,
  loadLibrary,
  refusedBy,
  searchLibraryContent,
  LIBRARY_PAGE_SIZE,
  type ContentMatch,
  type LibraryCategory,
  type LibraryFile,
  type LibrarySort,
  type LibrarySource,
} from "./chat-library";

/** The file library: uploads, files run_python generated and generated images in one owner-private list. */
export function ChatLibraryPage() {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const [sources, setSources] = useState<LibrarySource[]>([]);
  const [categories, setCategories] = useState<LibraryCategory[]>([]);
  const [sort, setSort] = useState<LibrarySort>("NEWEST");
  /** Name search reads the listing; content search asks the file search what a file contains. */
  const [mode, setMode] = useState<LibrarySearchMode>("name");
  /** Which slice of the library is on screen: the usable files, the favourites, what is arriving, the trash. */
  const [view, setView] = useState<LibraryView>("ready");
  const [renaming, setRenaming] = useState<LibraryFile>();
  const [layout, setLayout] = useState<LibraryLayout>("list");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<PreviewTarget>();
  const [confirming, setConfirming] = useState<LibraryFile[]>();
  const [refusals, setRefusals] = useState<string[]>([]);
  const [projectFiles, setProjectFiles] = useState<LibraryFile[]>();
  const [notice, setNotice] = useState<string>();

  /** A selection belongs to the page it was made on, so leaving that page drops it. */
  const showPage = (next: number) => {
    setOffset(next);
    setSelected([]);
  };
  const showFirstPage = () => showPage(0);
  /** Every filter change starts the list again: page 3 of the previous filter means nothing. */
  const fromTheFirstPage =
    <T,>(set: (next: T) => void) =>
    (value: T) => {
      set(value);
      showFirstPage();
    };

  const uploads = useLibraryUploads();
  const archive = useLibraryArchive();
  const filter = useMemo(
    () => ({
      query: mode === "content" ? "" : query,
      sources,
      categories,
      // The trash reads by when a file was deleted, not by when it was made.
      sort: view === "trash" ? ("DELETED" as const) : sort,
      favorite: view === "favorite" || undefined,
      status:
        view === "pending"
          ? ("PENDING" as const)
          : view === "trash"
            ? ("TRASH" as const)
            : undefined,
    }),
    [mode, query, sources, categories, sort, view],
  );
  const page = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, filter, offset],
    queryFn: ({ signal }) => loadLibrary(filter, offset, signal),
    // An upload being processed becomes usable on its own; the view follows without a manual refresh.
    refetchInterval: (current) =>
      current.state.data?.items.some((file) => file.status === "PROCESSING") ? 3000 : false,
  });
  const usage = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "usage"],
    queryFn: ({ signal }) => loadLibraryUsage(signal),
  });
  const trashWindow = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "trash-window"],
    queryFn: ({ signal }) => loadTrashWindow(signal),
    staleTime: 5 * 60_000,
  });
  const matches = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "content", query],
    queryFn: ({ signal }) => searchLibraryContent(query, signal),
    enabled: mode === "content" && query.length > 0,
  });
  const files = page.data?.items ?? [];
  const chosen = files.filter((file) => selected.includes(file.id));
  const filtered = query.length > 0 || sources.length > 0 || categories.length > 0;
  const searchingContent = mode === "content";

  /**
   * One request per file: a selection is normally part refused, and one refusal must not decide the rest. The
   * refusals are shown on the page rather than raised, because the dialog translates what it is handed and a
   * per-file message is runtime text, not a UI key.
   */
  const removeAll = async (targets: LibraryFile[]) => {
    const next: string[] = [];
    try {
      for (const file of targets) {
        try {
          await deleteLibraryFile(file, AbortSignal.timeout(30000));
        } catch (failure) {
          const holders = refusedBy(failure);
          next.push(
            `${file.filename}: ${holders.length > 0 ? ui("Đang dùng trong {{name}}", { name: holders.join(", ") }) : chatActionError(failure)}`,
          );
        }
      }
    } finally {
      setRefusals(next);
      // Deleting can empty the current page, so the list restarts where the remaining files are.
      showFirstPage();
      await cache.invalidateQueries({ queryKey: chatLibraryKey });
    }
  };

  /** One trash command: what it says when it worked, or the failure it names. */
  const act = async (run: () => Promise<string | void>, done: string) => {
    setRefusals([]);
    try {
      const said = await run();
      setNotice(typeof said === "string" ? said : done);
    } catch (failure) {
      setRefusals([chatActionError(failure)]);
    } finally {
      await cache.invalidateQueries({ queryKey: chatLibraryKey });
    }
  };

  const change = async (file: LibraryFile, next: { filename?: string; favorite?: boolean }) => {
    await changeLibraryFile(file, next, AbortSignal.timeout(30000));
    await cache.invalidateQueries({ queryKey: chatLibraryKey });
  };

  const rowActions = {
    onPreview: (file: LibraryFile) => setPreview(libraryPreviewTarget(file)),
    onDelete: (file: LibraryFile) => setConfirming([file]),
    onAddToProject: (file: LibraryFile) => setProjectFiles([file]),
    onRename: (file: LibraryFile) => setRenaming(file),
    onFavorite: (file: LibraryFile) => void change(file, { favorite: !file.favorite }),
    onRestore: (file: LibraryFile) =>
      act(
        () => restoreLibraryFile(file, AbortSignal.timeout(30000)),
        ui("Đã khôi phục {{name}}.", { name: file.filename }),
      ),
    onPurge: (file: LibraryFile) =>
      act(
        () => purgeLibraryFile(file, AbortSignal.timeout(30000)),
        ui("Đã xoá vĩnh viễn {{name}}.", { name: file.filename }),
      ),
    onRetried: () => cache.invalidateQueries({ queryKey: chatLibraryKey }),
    onRemovedFromProject: async (name: string) => {
      setNotice(ui("Đã gỡ khỏi dự án {{name}}.", { name }));
      await cache.invalidateQueries({ queryKey: chatLibraryKey });
    },
  };
  const clearFilters = () => {
    fromTheFirstPage(setSources)([]);
    setCategories([]);
    setSearch("");
  };

  return (
    <AppShell pageTitle={ui("Thư viện")}>
      <SettingsLayout wide>
        <LibraryDropZone onFiles={uploads.start}>
          <PageHeader
            title={ui("Thư viện")}
            description={
              page.data
                ? ui("{{count}} tệp · {{size}}", {
                    count: page.data.totalCount,
                    size: fileSize(page.data.totalBytes, i18n.language),
                  })
                : undefined
            }
            actions={<LibraryUploadButton onFiles={uploads.start} />}
          />

          <div className="mt-6 flex flex-col gap-6 lg:grid lg:grid-cols-[13rem_minmax(0,1fr)] lg:gap-8">
            <LibraryRail
              view={view}
              counts={{ [view]: page.data?.totalCount }}
              usage={usage.data}
              onView={(next) => {
                fromTheFirstPage(setView)(next);
                setRefusals([]);
                setNotice(undefined);
              }}
              onShowLargest={() => {
                fromTheFirstPage(setView)("ready");
                setSort("LARGEST");
              }}
            />

            <div className="flex min-w-0 flex-col gap-4">
              <LibraryToolbar
                sortable={view === "ready" || view === "favorite"}
                state={{ search, mode, sources, categories, sort, layout }}
                handlers={{
                  onSearch: fromTheFirstPage(setSearch),
                  onMode: fromTheFirstPage(setMode),
                  onSources: fromTheFirstPage(setSources),
                  onCategories: fromTheFirstPage(setCategories),
                  onSort: fromTheFirstPage(setSort),
                  onLayout: setLayout,
                }}
              />
              <LibraryFilterPills
                state={{ search, mode, sources, categories, sort, layout }}
                handlers={{
                  onSearch: fromTheFirstPage(setSearch),
                  onMode: fromTheFirstPage(setMode),
                  onSources: fromTheFirstPage(setSources),
                  onCategories: fromTheFirstPage(setCategories),
                  onSort: fromTheFirstPage(setSort),
                  onLayout: setLayout,
                }}
              />

              {selected.length > 0 && (
                <LibrarySelectionBar
                  count={selected.length}
                  packing={archive.state.phase === "packing"}
                  onDownload={() => void archive.start(chosen)}
                  onAddToProject={() => setProjectFiles(chosen)}
                  onDelete={() => setConfirming(chosen)}
                  onClear={() => setSelected([])}
                />
              )}

              <LibraryNotices
                archive={archive}
                notice={notice}
                refusals={refusals}
                onDismiss={() => {
                  setNotice(undefined);
                  setRefusals([]);
                }}
              />

              {view === "trash" && (
                <TrashBanner
                  days={trashWindow.data}
                  onEmpty={() =>
                    act(async () => {
                      const purged = await emptyLibraryTrash(AbortSignal.timeout(30000));
                      return ui("Đã xoá vĩnh viễn {{count}} tệp.", { count: purged });
                    }, ui("Đã dọn sạch thùng rác."))
                  }
                />
              )}

              {searchingContent ? (
                <ContentResults
                  query={query}
                  matches={matches}
                  onOpen={(file) => setPreview(libraryPreviewTarget(file))}
                  actions={rowActions}
                />
              ) : (
                <>
                  {page.isPending && <ListSkeleton />}
                  {page.isError && (
                    <Alert variant="destructive">
                      <AlertTitle>{ui("Không tải được thư viện.")}</AlertTitle>
                      <AlertDescription>
                        <Button prominence="internal" size="sm" onClick={() => void page.refetch()}>
                          {ui("Thử lại")}
                        </Button>
                      </AlertDescription>
                    </Alert>
                  )}
                  {page.isSuccess && files.length === 0 && (
                    <LibraryEmpty
                      view={view}
                      filtered={filtered}
                      action={<LibraryUploadButton onFiles={uploads.start} />}
                      onClearFilters={clearFilters}
                    />
                  )}
                  {files.length > 0 && (
                    <>
                      <div className="flex items-center gap-2 px-3">
                        <Checkbox
                          aria-label={ui("Chọn tất cả")}
                          checked={files.length > 0 && selected.length === files.length}
                          onCheckedChange={(checked) =>
                            setSelected(checked ? files.map((file) => file.id) : [])
                          }
                        />
                        <span className="font-secondary-body text-content-muted">
                          {ui("Chọn tất cả")}
                        </span>
                      </div>
                      <LibraryList
                        files={files}
                        view={view}
                        layout={layout}
                        selected={selected}
                        grouped={sort === "NEWEST" && view !== "trash"}
                        actions={rowActions}
                        onSelect={(file) =>
                          setSelected(
                            selected.includes(file.id)
                              ? selected.filter((id) => id !== file.id)
                              : [...selected, file.id],
                          )
                        }
                      />
                    </>
                  )}
                  {(offset > 0 || page.data?.hasMore) && (
                    <TablePagination
                      label={ui("Phân trang thư viện")}
                      page={Math.floor(offset / LIBRARY_PAGE_SIZE)}
                      totalPages={
                        page.data ? Math.ceil(page.data.totalCount / LIBRARY_PAGE_SIZE) : undefined
                      }
                      previousDisabled={offset === 0}
                      nextDisabled={!page.data?.hasMore}
                      previousLabel={ui("Trang trước")}
                      nextLabel={ui("Trang sau")}
                      onPrevious={() => showPage(Math.max(0, offset - LIBRARY_PAGE_SIZE))}
                      onNext={() => showPage(offset + LIBRARY_PAGE_SIZE)}
                    />
                  )}
                </>
              )}
            </div>
          </div>
        </LibraryDropZone>
      </SettingsLayout>

      {preview && (
        <ChatFilePreviewModal
          target={preview}
          siblings={files.map(libraryPreviewTarget)}
          onClose={() => setPreview(undefined)}
        />
      )}
      <LibraryUploadTray
        uploads={uploads.uploads}
        onCancel={uploads.cancel}
        onDismiss={uploads.dismiss}
      />
      {renaming && (
        <RenameDialog
          file={renaming}
          onOpenChange={(open) => !open && setRenaming(undefined)}
          onRename={async (filename) => {
            await change(renaming, { filename });
            setRenaming(undefined);
          }}
        />
      )}
      <ChatAddToProjectDialog
        files={projectFiles}
        onOpenChange={(open) => !open && setProjectFiles(undefined)}
        onAdded={(name) => {
          setSelected([]);
          setNotice(ui("Đã thêm vào dự án {{name}}.", { name }));
        }}
      />
      <ConfirmDialog
        open={confirming !== undefined}
        onOpenChange={(open) => !open && setConfirming(undefined)}
        title={ui("Xoá tệp?")}
        description={
          trashWindow.data === 0
            ? ui(
                "Tệp sẽ bị xoá khỏi mọi cuộc hội thoại và không thể khôi phục. Đã chọn {{count}} tệp.",
                { count: confirming?.length ?? 0 },
              )
            : ui(
                "Tệp sẽ rời khỏi mọi cuộc hội thoại và nằm trong thùng rác {{days}} ngày, khôi phục được trong thời gian đó. Đã chọn {{count}} tệp.",
                { days: trashWindow.data ?? 30, count: confirming?.length ?? 0 },
              )
        }
        confirmLabel={ui("Xoá")}
        pendingLabel={ui("Đang xoá…")}
        confirmTone="danger"
        onConfirm={() => removeAll(confirming ?? [])}
      />
    </AppShell>
  );
}

/** Everything the page has to say about the last command, in one place instead of five stacked paragraphs. */
function LibraryNotices({
  archive,
  notice,
  refusals,
  onDismiss,
}: {
  archive: ReturnType<typeof useLibraryArchive>;
  notice?: string;
  refusals: string[];
  onDismiss: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <>
      {archive.state.phase === "packing" && (
        <Alert>
          <Download />
          <AlertTitle>
            {ui("Đang đóng gói {{count}} tệp thành ZIP…", { count: archive.state.fileCount })}
          </AlertTitle>
        </Alert>
      )}
      {archive.state.phase === "ready" && (
        <Alert variant="success">
          <Download />
          <AlertTitle>{ui("ZIP đã sẵn sàng và đang được tải về.")}</AlertTitle>
          <AlertDescription>
            {archive.state.archive.skipped.length > 0 &&
              ui("Bỏ qua {{count}} tệp không còn khả dụng: {{names}}", {
                count: archive.state.archive.skipped.length,
                names: archive.state.archive.skipped.join(", "),
              })}
            <Button size="sm" prominence="internal" asChild>
              <a
                href={archiveContentUrl(archive.state.archive.id)}
                download
                onClick={() => archive.reset()}
              >
                {ui("Tải lại ZIP")}
              </a>
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {archive.state.phase === "failed" && (
        <Alert variant="destructive">
          <AlertTitle>
            {archive.state.message ||
              ui("Không đóng gói được ZIP. Hãy chọn ít tệp hơn rồi thử lại.")}
          </AlertTitle>
        </Alert>
      )}
      {notice && (
        <Alert variant="success">
          <AlertTitle>{notice}</AlertTitle>
          <AlertDescription>
            <Button size="sm" prominence="internal" onClick={onDismiss}>
              {ui("Đóng")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {refusals.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Một số tệp không xoá được")}</AlertTitle>
          <AlertDescription>
            <ul className="flex flex-col gap-1">
              {refusals.map((refusal) => (
                <li key={refusal}>{refusal}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
    </>
  );
}

/** How long the trash keeps a file, and the one command that applies to all of it. */
function TrashBanner({ days, onEmpty }: { days?: number; onEmpty: () => Promise<void> }) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border-subtle bg-surface-subtle px-3 py-2">
      <p className="font-secondary-body text-content-secondary">
        {days === 0
          ? ui("Máy chủ này xoá tệp ngay, không giữ trong thùng rác.")
          : ui("Tệp đã xoá được giữ {{days}} ngày rồi xoá vĩnh viễn.", { days: days ?? 30 })}
      </p>
      <ConfirmDialog
        trigger={
          <Button size="sm" tone="danger" prominence="secondary">
            <Trash2 className="size-4" aria-hidden="true" />
            {ui("Dọn sạch thùng rác")}
          </Button>
        }
        title={ui("Dọn sạch thùng rác?")}
        description={ui("Mọi tệp trong thùng rác sẽ bị xoá vĩnh viễn và không thể khôi phục.")}
        confirmLabel={ui("Dọn sạch")}
        pendingLabel={ui("Đang dọn…")}
        confirmTone="danger"
        onConfirm={onEmpty}
      />
    </div>
  );
}

/** What a phrase inside a file found, which is a different list from the library's own. */
function ContentResults({
  query,
  matches,
  onOpen,
  actions,
}: {
  query: string;
  matches: {
    data?: ContentMatch[];
    isFetching: boolean;
    isError: boolean;
    isSuccess: boolean;
    refetch: () => unknown;
  };
  onOpen: (file: LibraryFile) => void;
  actions: Parameters<typeof FileActions>[0]["actions"];
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-col gap-3">
      <p className="font-secondary-body text-content-muted">
        {ui(
          "Tìm trong nội dung tệp bạn đã tải lên và đã lập chỉ mục. Tệp do Chat tạo chỉ tìm được theo tên.",
        )}
      </p>
      {query.length === 0 && (
        <p role="status" className="text-content-muted">
          {ui("Nhập điều bạn nhớ về nội dung tệp.")}
        </p>
      )}
      {matches.isFetching && <ListSkeleton />}
      {matches.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tìm được trong nội dung tệp.")}</AlertTitle>
          <AlertDescription>
            <Button prominence="internal" size="sm" onClick={() => void matches.refetch()}>
              {ui("Thử lại")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {matches.isSuccess &&
        !matches.isFetching &&
        matches.data?.length === 0 &&
        query.length > 0 && (
          <p role="status" className="text-content-muted">
            {ui("Không có tệp nào khớp nội dung này.")}
          </p>
        )}
      {matches.data && matches.data.length > 0 && (
        <LibraryContentMatches
          matches={matches.data}
          query={query}
          onOpen={(match) => onOpen(match.file)}
          actions={(match) => <FileActions file={match.file} actions={actions} />}
        />
      )}
    </div>
  );
}

function ListSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-hidden="true">
      {[0, 1, 2, 3, 4].map((row) => (
        <Skeleton key={row} className="h-14 w-full rounded-lg" />
      ))}
    </div>
  );
}

/** Renaming keeps the extension, so the dialog explains the file's new name before it is saved. */
function RenameDialog({
  file,
  onOpenChange,
  onRename,
}: {
  file: LibraryFile;
  onOpenChange: (open: boolean) => void;
  onRename: (filename: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [name, setName] = useState(file.filename);
  return (
    <ChatDialog
      open
      onOpenChange={onOpenChange}
      title={ui("Đổi tên tệp")}
      description={ui("Tên mới hiển thị ở mọi nơi và khi tải về. Phần đuôi tệp được giữ nguyên.")}
      submitLabel={ui("Đổi tên")}
      submitDisabled={name.trim().length === 0}
      onSubmit={() => onRename(name)}
    >
      <label className="block space-y-1">
        <span>{ui("Tên tệp")}</span>
        <Input
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={200}
          autoFocus
        />
      </label>
    </ChatDialog>
  );
}
