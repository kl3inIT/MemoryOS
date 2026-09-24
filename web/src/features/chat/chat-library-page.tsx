import { useDeferredValue, useEffect, useMemo, useState } from "react";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { Download, Trash2, X } from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Skeleton } from "@/components/ui/skeleton";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { TablePagination } from "@/components/ui/table-pagination";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { chatSessionsKey, newChatSession } from "./chat-api";
import { chatActionError } from "./chat-action-utils";
import { ChatAddToProjectDialog } from "./chat-add-to-project";
import { ChatDialog } from "./chat-dialog";
import { LibraryContentMatches } from "./chat-library-content";
import { LibraryDropZone, LibraryUploadButton, LibraryUploadTray } from "./chat-library-uploads";
import { LibraryRail, type LibraryView } from "./chat-library-rail";
import { LibrarySettingsButton } from "./chat-library-settings";
import { FileActions, LibraryEmpty, LibraryList } from "./chat-library-rows";
import {
  LibraryFilterPills,
  LibrarySelectionBar,
  LibraryToolbar,
  type LibraryLayout,
  type LibrarySearchMode,
} from "./chat-library-toolbar";
import { archiveContentUrl, useLibraryArchive, type LibraryArchive } from "./use-library-archive";
import { useLibraryUploads } from "./use-library-uploads";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import { type AskExtras } from "./chat-file-ask-composer";
import { uploadChatFile } from "./chat-files";
import { type PreviewTarget } from "./chat-file-preview";
import {
  changeLibraryFile,
  chatLibraryKey,
  deleteLibraryFile,
  libraryUpload,
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
  LIBRARY_PAGE_SIZES,
  type ContentMatch,
  type LibraryCategory,
  type LibraryFile,
  type LibrarySort,
  type LibrarySource,
} from "./chat-library";

/** The file library: uploads, files run_python generated and generated images in one owner-private list. */
export function ChatLibraryPage() {
  const ui = useAppTranslation();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const [sources, setSources] = useState<LibrarySource[]>([]);
  // A link from the storage page names one category; the filters then behave like any other filter. The
  // search is read without binding to the route, so the page also renders where that route is not mounted.
  const linked = useSearch({ strict: false }).category as LibraryCategory | undefined;
  const [categories, setCategories] = useState<LibraryCategory[]>(linked ? [linked] : []);
  const [sort, setSort] = useState<LibrarySort>("NEWEST");
  /** Name search reads the listing; content search asks the file search what a file contains. */
  const [mode, setMode] = useState<LibrarySearchMode>("name");
  /** Which slice of the library is on screen: the usable files, the favourites, what is arriving, the trash. */
  const [view, setView] = useState<LibraryView>("ready");
  const [renaming, setRenaming] = useState<LibraryFile>();
  const [layout, setLayout] = useState<LibraryLayout>("list");
  const [offset, setOffset] = useState(0);
  const [size, setSize] = useState<number>(LIBRARY_PAGE_SIZE);
  const [selected, setSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<LibraryFile>();
  const [confirming, setConfirming] = useState<LibraryFile[]>();
  const [refusals, setRefusals] = useState<string[]>([]);
  const [projectFiles, setProjectFiles] = useState<LibraryFile[]>();
  const notify = useActionNotifications();
  const [purging, setPurging] = useState<LibraryFile[]>();

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
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, filter, offset, size],
    queryFn: ({ signal }) => loadLibrary(filter, offset, signal, size),
    // Paging keeps the page being read on screen until the next one arrives, instead of emptying the list.
    placeholderData: keepPreviousData,
    // An upload being processed becomes usable on its own; the view follows without a manual refresh.
    refetchInterval: (current) =>
      current.state.data?.items.some((file) => file.status === "PROCESSING") ? 3000 : false,
  });
  // The next page is fetched while this one is read, so *Tiếp* shows it without a wait.
  useEffect(() => {
    if (!page.data?.hasMore || page.isPlaceholderData) return;
    void cache.prefetchQuery({
      queryKey: [...chatLibraryKey, actorId, authorizationVersion, filter, offset + size, size],
      queryFn: ({ signal }) => loadLibrary(filter, offset + size, signal, size),
      staleTime: 30_000,
    });
  }, [
    cache,
    actorId,
    authorizationVersion,
    filter,
    offset,
    size,
    page.data?.hasMore,
    page.isPlaceholderData,
  ]);
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
    let deleted = 0;
    try {
      for (const file of targets) {
        try {
          await deleteLibraryFile(file, AbortSignal.timeout(30000));
          deleted += 1;
        } catch (failure) {
          const holders = refusedBy(failure);
          next.push(
            `${file.filename}: ${holders.length > 0 ? ui("Đang dùng trong {{name}}", { name: holders.join(", ") }) : chatActionError(failure)}`,
          );
        }
      }
    } finally {
      setRefusals(next);
      if (deleted > 0)
        notify({
          title:
            trashWindow.data === 0
              ? ui("Đã xoá vĩnh viễn {{count}} tệp.", { count: deleted })
              : ui("Đã chuyển {{count}} tệp vào thùng rác.", { count: deleted }),
          tone: "success",
        });
      // Deleting can empty the current page, so the list restarts where the remaining files are.
      showFirstPage();
      await cache.invalidateQueries({ queryKey: chatLibraryKey });
    }
  };

  /**
   * The same command over a selection: a trash command is refused per file like a delete, so one failure is
   * reported rather than deciding the rest.
   */
  const eachChosen = async (
    targets: LibraryFile[],
    run: (file: LibraryFile) => Promise<void>,
    done: (count: number) => string,
  ) => {
    const failures: string[] = [];
    let succeeded = 0;
    for (const file of targets) {
      try {
        await run(file);
        succeeded += 1;
      } catch (failure) {
        failures.push(`${file.filename}: ${chatActionError(failure)}`);
      }
    }
    setRefusals(failures);
    if (succeeded > 0) notify({ title: done(succeeded), tone: "success" });
    setSelected([]);
    showFirstPage();
    await cache.invalidateQueries({ queryKey: chatLibraryKey });
  };

  /** One trash command: what it says when it worked, or the failure it names. */
  const act = async (run: () => Promise<string | void>, done: string) => {
    setRefusals([]);
    try {
      const said = await run();
      notify({ title: typeof said === "string" ? said : done, tone: "success" });
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

  const favorite = async (file: LibraryFile) => {
    try {
      await change(file, { favorite: !file.favorite });
    } catch (failure) {
      notify({ title: chatActionError(failure), tone: "error" });
    }
  };

  /**
   * A question asked where the file is read (MEM-152): the file becomes an upload, a conversation is created
   * for it, and Chat attaches it and sends the question once the conversation is open. An empty question
   * opens that conversation with the files attached and nothing sent. A crop applied in the preview is asked
   * about as itself, so the question is about what was on screen rather than the untouched original.
   */
  const askAboutFile = async (
    target: PreviewTarget,
    question: string,
    extras: AskExtras & { edited?: File },
  ) => {
    const file =
      files.find((item) => item.id === target.id) ??
      (preview?.id === target.id ? preview : undefined);
    const signal = AbortSignal.timeout(120_000);
    const subject = extras.edited
      ? await uploadChatFile(extras.edited, crypto.randomUUID(), signal, () => {})
      : file && (await libraryUpload(file, signal));
    if (!subject) return;
    const attach = [subject.id];
    for (const chosen of extras.library) attach.push((await libraryUpload(chosen, signal)).id);
    for (const chosen of extras.uploads)
      attach.push((await uploadChatFile(chosen, crypto.randomUUID(), signal, () => {})).id);
    const session = await newChatSession(question || subject.filename, signal);
    await cache.invalidateQueries({ queryKey: chatSessionsKey });
    await cache.invalidateQueries({ queryKey: chatLibraryKey });
    await navigate({
      to: "/chat/$sessionId",
      params: { sessionId: session.id },
      search: { ask: question || undefined, attach },
    });
  };

  const rowActions = {
    onPreview: setPreview,
    onDelete: (file: LibraryFile) => setConfirming([file]),
    onAddToProject: (file: LibraryFile) => setProjectFiles([file]),
    onRename: (file: LibraryFile) => setRenaming(file),
    onFavorite: (file: LibraryFile) => void favorite(file),
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
      notify({ title: ui("Đã gỡ khỏi dự án {{name}}.", { name }), tone: "success" });
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
            actions={
              <>
                <LibraryUploadButton onFiles={uploads.start} />
                <LibrarySettingsButton
                  usage={usage.data}
                  usageFailed={usage.isError}
                  trashDays={trashWindow.data}
                  onCategory={(category) => {
                    setView("ready");
                    fromTheFirstPage(setCategories)([category]);
                  }}
                />
              </>
            }
          />

          <div className="mt-6 flex flex-col gap-6 lg:grid lg:grid-cols-[13rem_minmax(0,1fr)] lg:gap-8">
            <LibraryRail
              view={view}
              counts={{ [view]: page.data?.totalCount }}
              usage={usage.data}
              onView={(next) => {
                fromTheFirstPage(setView)(next);
                setRefusals([]);
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

              {/* Always rendered: an empty selection is the bar leaving, which it animates itself. */}
              <LibrarySelectionBar
                count={selected.length}
                packing={archive.state.phase === "packing"}
                trash={view === "trash"}
                onDownload={() => void archive.start(chosen)}
                onAddToProject={() => setProjectFiles(chosen)}
                onDelete={() => setConfirming(chosen)}
                onRestore={() =>
                  void eachChosen(
                    chosen,
                    (file) => restoreLibraryFile(file, AbortSignal.timeout(30000)),
                    (count) => ui("Đã khôi phục {{count}} tệp.", { count }),
                  )
                }
                onPurge={() => setPurging(chosen)}
                onClear={() => setSelected([])}
              />

              <LibraryNotices
                archive={archive}
                refusals={refusals}
                onDismiss={() => setRefusals([])}
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
                  onOpen={setPreview}
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
                    // While the next page is on its way the one being read stays, dimmed rather than gone.
                    <div
                      aria-busy={page.isPlaceholderData}
                      className={cn(
                        "flex flex-col gap-4 transition-opacity",
                        page.isPlaceholderData && "opacity-60",
                      )}
                    >
                      {/* A list header, aligned to the rows' own checkbox column so the three checkbox
                          gutters (page, day, file) read as one line down the page. */}
                      <div className="flex items-center border-b border-border-subtle px-[13px] pb-2">
                        <label className="flex cursor-pointer items-center gap-2 font-secondary-body text-content-muted">
                          <Checkbox
                            aria-label={ui("Chọn tất cả trên trang này")}
                            checked={
                              selected.length === 0
                                ? false
                                : selected.length === files.length
                                  ? true
                                  : "indeterminate"
                            }
                            onCheckedChange={(checked) =>
                              setSelected(checked === true ? files.map((file) => file.id) : [])
                            }
                          />
                          {/* The scope is on the label, because a selection never leaves its page. */}
                          {ui("Chọn tất cả trên trang này")}
                        </label>
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
                        onSelectDay={(day, pick) => {
                          const ids = day.map((file) => file.id);
                          setSelected(
                            pick
                              ? [...selected, ...ids.filter((id) => !selected.includes(id))]
                              : selected.filter((id) => !ids.includes(id)),
                          );
                        }}
                      />
                    </div>
                  )}
                  {/* The bar stays while there are files, because it also carries the page size. */}
                  {page.data && page.data.totalCount > 0 && (
                    <TablePagination
                      label={ui("Phân trang thư viện")}
                      className="px-0"
                      page={Math.floor(offset / size)}
                      totalPages={Math.ceil(page.data.totalCount / size)}
                      summary={ui("Hiển thị {{first}}–{{last}} trên {{total}} tệp", {
                        first: offset + 1,
                        last: Math.min(offset + size, page.data.totalCount),
                        total: page.data.totalCount,
                      })}
                      previousDisabled={offset === 0}
                      nextDisabled={!page.data.hasMore}
                      previousLabel={ui("Trang trước")}
                      nextLabel={ui("Trang sau")}
                      onPrevious={() => showPage(Math.max(0, offset - size))}
                      onNext={() => showPage(offset + size)}
                    >
                      <PageSizeSelect
                        label={ui("Số tệp mỗi trang")}
                        rowsLabel={ui("Số tệp")}
                        value={size}
                        sizes={LIBRARY_PAGE_SIZES}
                        disabled={page.isPlaceholderData}
                        // A page size change re-cuts the list, so it restarts at its first page.
                        onSizeChange={(next) => {
                          setSize(next);
                          showFirstPage();
                        }}
                      />
                    </TablePagination>
                  )}
                </>
              )}
            </div>
          </div>
        </LibraryDropZone>
      </SettingsLayout>

      {preview && (
        <ChatFilePreviewModal
          target={libraryPreviewTarget(preview)}
          siblings={files.map(libraryPreviewTarget)}
          onClose={() => setPreview(undefined)}
          onSaveImage={(file) => uploads.start([file])}
          onAsk={askAboutFile}
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
          notify({ title: ui("Đã thêm vào dự án {{name}}.", { name }), tone: "success" });
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
      <ConfirmDialog
        open={purging !== undefined}
        onOpenChange={(open) => !open && setPurging(undefined)}
        title={ui("Xoá vĩnh viễn?")}
        description={ui("Tệp sẽ bị xoá vĩnh viễn và không thể khôi phục. Đã chọn {{count}} tệp.", {
          count: purging?.length ?? 0,
        })}
        confirmLabel={ui("Xoá vĩnh viễn")}
        pendingLabel={ui("Đang xoá…")}
        confirmTone="danger"
        onConfirm={async () => {
          await eachChosen(
            purging ?? [],
            (file) => purgeLibraryFile(file, AbortSignal.timeout(30000)),
            (count) => ui("Đã xoá vĩnh viễn {{count}} tệp.", { count }),
          );
          setPurging(undefined);
        }}
      />
    </AppShell>
  );
}

/**
 * What the page itself must keep on screen: the ZIP a selection is waiting for, and the files a command
 * refused one by one. What simply succeeded is said by the application's own notifications instead, as every
 * other page says it.
 */
function LibraryNotices({
  archive,
  refusals,
  onDismiss,
}: {
  archive: LibraryArchive;
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
      {refusals.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Một số tệp không xoá được")}</AlertTitle>
          <AlertAction>
            <IconButton size="sm" prominence="internal" aria-label={ui("Đóng")} onClick={onDismiss}>
              <X />
            </IconButton>
          </AlertAction>
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
