import { useDeferredValue, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  Download,
  FileSpreadsheet,
  FileText,
  FolderPlus,
  Image as ImageIcon,
  LayoutGrid,
  List,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Presentation,
  RotateCcw,
  Search,
  Star,
  Trash2,
} from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Checkbox } from "@/components/ui/checkbox";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { PageHeader, SettingsLayout } from "@/components/ui/settings-layout";
import { Select } from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { cn } from "@/lib/utils";
import { chatActionError } from "./chat-action-utils";
import { ChatAddToProjectDialog } from "./chat-add-to-project";
import { ChatDialog } from "./chat-dialog";
import { LibraryContentMatches } from "./chat-library-content";
import { LibraryDropZone, LibraryUploadButton, LibraryUploadTray } from "./chat-library-uploads";
import { useLibraryUploads } from "./use-library-uploads";
import { fileSize } from "./chat-code";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import { downloadUrl, type PreviewTarget } from "./chat-file-preview";
import { imageArtifactUrl } from "./chat-image";
import {
  changeLibraryFile,
  chatLibraryKey,
  deleteLibraryFile,
  groupByDay,
  libraryPreviewTarget,
  loadLibrary,
  refusedBy,
  removeFromProject,
  searchLibraryContent,
  usageLabel,
  LIBRARY_PAGE_SIZE,
  type LibraryCategory,
  type LibraryFile,
  type LibrarySort,
  type LibrarySource,
} from "./chat-library";
import { retryChatFile } from "@/lib/hey-api/sdk.gen";
import { sameOriginMutationHeaders } from "@/lib/api";

const SOURCES: LibrarySource[] = ["UPLOAD", "GENERATED", "IMAGE"];
const CATEGORIES: LibraryCategory[] = ["DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER"];

function categoryIcon(category: LibraryCategory) {
  switch (category) {
    case "SPREADSHEET":
      return <FileSpreadsheet className="size-4 text-content-muted" />;
    case "IMAGE":
      return <ImageIcon className="size-4 text-content-muted" />;
    case "PRESENTATION":
      return <Presentation className="size-4 text-content-muted" />;
    default:
      return <FileText className="size-4 text-content-muted" />;
  }
}

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
  const [mode, setMode] = useState<"name" | "content">("name");
  /** The usable files, or the uploads still being processed and the ones that failed. */
  const [view, setView] = useState<"ready" | "pending">("ready");
  const [favorite, setFavorite] = useState(false);
  const [renaming, setRenaming] = useState<LibraryFile>();
  const [layout, setLayout] = useState<"table" | "grid">("table");
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

  const uploads = useLibraryUploads();
  const filter = useMemo(
    () => ({
      query: mode === "content" ? "" : query,
      sources,
      categories,
      sort,
      favorite: favorite || undefined,
      status: view === "pending" ? ("PENDING" as const) : undefined,
    }),
    [mode, query, sources, categories, sort, favorite, view],
  );
  const page = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, filter, offset],
    queryFn: ({ signal }) => loadLibrary(filter, offset, signal),
    // An upload being processed becomes usable on its own; the view follows without a manual refresh.
    refetchInterval: (current) =>
      current.state.data?.items.some((file) => file.status === "PROCESSING") ? 3000 : false,
  });
  const matches = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "content", query],
    queryFn: ({ signal }) => searchLibraryContent(query, signal),
    enabled: mode === "content" && query.length > 0,
  });
  const files = page.data?.items ?? [];
  const groups = groupByDay(files);
  // Every file can be selected: adding to a Project applies to all of them, and a delete names each refusal.
  const selectable = files;
  const chosen = files.filter((file) => selected.includes(file.id));

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

  const change = async (file: LibraryFile, next: { filename?: string; favorite?: boolean }) => {
    await changeLibraryFile(file, next, AbortSignal.timeout(30000));
    await cache.invalidateQueries({ queryKey: chatLibraryKey });
  };

  const sourceLabels: Record<LibrarySource, string> = {
    UPLOAD: ui("Đã tải lên"),
    GENERATED: ui("Do mã tạo"),
    IMAGE: ui("Ảnh AI"),
  };
  const categoryLabels: Record<LibraryCategory, string> = {
    DOCUMENT: ui("Tài liệu"),
    SPREADSHEET: ui("Bảng tính"),
    IMAGE: ui("Ảnh"),
    PRESENTATION: ui("Trình chiếu"),
    OTHER: ui("Khác"),
  };
  const groupLabels = {
    today: ui("Hôm nay"),
    yesterday: ui("Hôm qua"),
    earlier: ui("Trước đó"),
  };

  /** Every filter change starts the list again: page 3 of the previous filter means nothing. */
  const fromTheFirstPage =
    <T,>(set: (next: T) => void) =>
    (value: T) => {
      set(value);
      showFirstPage();
    };
  const toggle = <T extends string>(values: T[], value: T, set: (next: T[]) => void) =>
    fromTheFirstPage(set)(
      values.includes(value) ? values.filter((item) => item !== value) : [...values, value],
    );

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
            actions={
              <div className="flex items-center gap-2">
                <LibraryUploadButton onFiles={uploads.start} />
                <Tabs
                  value={layout}
                  onValueChange={(value) => setLayout(value as "table" | "grid")}
                >
                  <TabsList aria-label={ui("Cách hiển thị")}>
                    <TabsTrigger value="table" aria-label={ui("Dạng bảng")}>
                      <List className="size-4" />
                    </TabsTrigger>
                    <TabsTrigger value="grid" aria-label={ui("Dạng lưới")}>
                      <LayoutGrid className="size-4" />
                    </TabsTrigger>
                  </TabsList>
                </Tabs>
              </div>
            }
          />

          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-3">
              <div className="relative min-w-0 flex-1">
                <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted" />
                <Input
                  value={search}
                  onChange={(event) => fromTheFirstPage(setSearch)(event.target.value)}
                  placeholder={
                    mode === "content" ? ui("Tìm trong nội dung tệp") : ui("Tìm theo tên tệp")
                  }
                  aria-label={
                    mode === "content" ? ui("Tìm trong nội dung tệp") : ui("Tìm theo tên tệp")
                  }
                  maxLength={200}
                  className="pl-9"
                />
              </div>
              <Tabs
                value={mode}
                onValueChange={(value) => fromTheFirstPage(setMode)(value as "name" | "content")}
              >
                <TabsList aria-label={ui("Cách tìm")}>
                  <TabsTrigger value="name">{ui("Tên")}</TabsTrigger>
                  <TabsTrigger value="content">{ui("Nội dung")}</TabsTrigger>
                </TabsList>
              </Tabs>
              {mode === "name" && (
                <Select
                  className="w-52"
                  aria-label={ui("Sắp xếp")}
                  value={sort}
                  onChange={(event) => fromTheFirstPage(setSort)(event.target.value as LibrarySort)}
                >
                  <option value="NEWEST">{ui("Mới nhất")}</option>
                  <option value="OLDEST">{ui("Cũ nhất")}</option>
                  <option value="NAME">{ui("Tên A → Z")}</option>
                  <option value="LARGEST">{ui("Dung lượng giảm dần")}</option>
                  <option value="SMALLEST">{ui("Dung lượng tăng dần")}</option>
                </Select>
              )}
            </div>
            <div className="flex flex-wrap gap-2" role="group" aria-label={ui("Lọc tệp")}>
              {SOURCES.map((source) => (
                <FilterToggle
                  key={source}
                  label={sourceLabels[source]}
                  pressed={sources.includes(source)}
                  onToggle={() => toggle(sources, source, setSources)}
                />
              ))}
              <span className="mx-1 w-px self-stretch bg-border-default" aria-hidden />
              {CATEGORIES.map((category) => (
                <FilterToggle
                  key={category}
                  label={categoryLabels[category]}
                  pressed={categories.includes(category)}
                  onToggle={() => toggle(categories, category, setCategories)}
                />
              ))}
              <span className="mx-1 w-px self-stretch bg-border-default" aria-hidden />
              <FilterToggle
                label={ui("Yêu thích")}
                pressed={favorite}
                onToggle={() => fromTheFirstPage(setFavorite)(!favorite)}
              />
            </div>
            {mode === "name" && (
              <Tabs
                value={view}
                onValueChange={(value) => fromTheFirstPage(setView)(value as "ready" | "pending")}
              >
                <TabsList aria-label={ui("Trạng thái tệp")}>
                  <TabsTrigger value="ready">{ui("Tệp")}</TabsTrigger>
                  <TabsTrigger value="pending">{ui("Đang xử lý / Lỗi")}</TabsTrigger>
                </TabsList>
              </Tabs>
            )}
          </div>

          {selected.length > 0 && (
            <div className="flex items-center gap-3 rounded-lg border border-border-default bg-surface-raised px-4 py-2">
              <span className="text-sm">
                {ui("Đã chọn {{count}} tệp", { count: selected.length })}
              </span>
              <Button size="sm" prominence="secondary" onClick={() => setProjectFiles(chosen)}>
                <FolderPlus className="size-4" />
                {ui("Thêm vào dự án")}
              </Button>
              <Button size="sm" tone="danger" onClick={() => setConfirming(chosen)}>
                <Trash2 className="size-4" />
                {ui("Xoá")}
              </Button>
              <Button size="sm" prominence="internal" onClick={() => setSelected([])}>
                {ui("Bỏ chọn")}
              </Button>
            </div>
          )}
          {notice && (
            <p role="status" className="text-sm text-content-secondary">
              {notice}
            </p>
          )}
          {refusals.length > 0 && (
            <ul role="alert" className="flex flex-col gap-1 text-sm text-content-danger">
              {refusals.map((refusal) => (
                <li key={refusal}>{refusal}</li>
              ))}
            </ul>
          )}

          {mode === "content" && (
            <>
              <p className="text-sm text-content-muted">
                {ui(
                  "Tìm trong nội dung tệp bạn đã tải lên và đã lập chỉ mục. Tệp do Chat tạo chỉ tìm được theo tên.",
                )}
              </p>
              {query.length === 0 && (
                <p role="status" className="text-content-muted">
                  {ui("Nhập điều bạn nhớ về nội dung tệp.")}
                </p>
              )}
              {matches.isFetching && <p role="status">{ui("Đang tìm…")}</p>}
              {matches.isError && (
                <p role="alert">
                  {ui("Không tìm được trong nội dung tệp.")}{" "}
                  <Button prominence="internal" size="sm" onClick={() => void matches.refetch()}>
                    {ui("Thử lại")}
                  </Button>
                </p>
              )}
              {matches.isSuccess &&
                !matches.isFetching &&
                matches.data.length === 0 &&
                query.length > 0 && (
                  <p role="status" className="text-content-muted">
                    {ui("Không có tệp nào khớp nội dung này.")}
                  </p>
                )}
              {matches.data && matches.data.length > 0 && (
                <LibraryContentMatches
                  matches={matches.data}
                  query={query}
                  onOpen={(match) => setPreview(libraryPreviewTarget(match.file))}
                  actions={(match) => (
                    <FileActions
                      file={match.file}
                      onDelete={() => setConfirming([match.file])}
                      onAddToProject={() => setProjectFiles([match.file])}
                      onRename={() => setRenaming(match.file)}
                      onFavorite={() => void change(match.file, { favorite: !match.file.favorite })}
                    />
                  )}
                />
              )}
            </>
          )}

          {mode === "name" && page.isPending && <p role="status">{ui("Đang tải thư viện…")}</p>}
          {mode === "name" && page.isError && (
            <p role="alert">
              {ui("Không tải được thư viện.")}{" "}
              <Button prominence="internal" size="sm" onClick={() => void page.refetch()}>
                {ui("Thử lại")}
              </Button>
            </p>
          )}
          {mode === "name" && page.isSuccess && files.length === 0 && (
            <p role="status" className="text-content-muted">
              {view === "pending"
                ? ui("Không có tệp nào đang xử lý hoặc bị lỗi.")
                : query || sources.length > 0 || categories.length > 0 || favorite
                  ? ui("Không có tệp nào khớp bộ lọc.")
                  : ui("Chưa có tệp nào. Tải tệp lên hoặc để Chat tạo ra, tệp sẽ xuất hiện ở đây.")}
            </p>
          )}

          {mode === "name" &&
            files.length > 0 &&
            (layout === "table" ? (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-10">
                      <Checkbox
                        aria-label={ui("Chọn tất cả")}
                        checked={selectable.length > 0 && selected.length === selectable.length}
                        onCheckedChange={(checked) =>
                          setSelected(checked ? selectable.map((file) => file.id) : [])
                        }
                      />
                    </TableHead>
                    <TableHead>{ui("Tên tệp")}</TableHead>
                    <TableHead>{view === "pending" ? ui("Trạng thái") : ui("Nguồn tệp")}</TableHead>
                    <TableHead>{ui("Dung lượng")}</TableHead>
                    <TableHead>{ui("Ngày tạo")}</TableHead>
                    <TableHead className="text-right">{ui("Thao tác")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {files.map((file) => (
                    <TableRow key={file.id}>
                      <TableCell>
                        <Checkbox
                          aria-label={ui("Chọn {{name}}", { name: file.filename })}
                          checked={selected.includes(file.id)}
                          onCheckedChange={() =>
                            setSelected(
                              selected.includes(file.id)
                                ? selected.filter((id) => id !== file.id)
                                : [...selected, file.id],
                            )
                          }
                        />
                      </TableCell>
                      <TableCell>
                        <button
                          type="button"
                          className="flex min-w-0 items-center gap-2 text-left hover:underline"
                          onClick={() => setPreview(libraryPreviewTarget(file))}
                        >
                          {categoryIcon(file.category)}
                          <span className="truncate">{file.filename}</span>
                        </button>
                        <FileUsage
                          file={file}
                          onRemoved={async (name) => {
                            setNotice(ui("Đã gỡ khỏi dự án {{name}}.", { name }));
                            await cache.invalidateQueries({ queryKey: chatLibraryKey });
                          }}
                        />
                      </TableCell>
                      <TableCell className="text-content-secondary">
                        {view === "pending" ? statusLabel(file, ui) : sourceLabels[file.source]}
                      </TableCell>
                      <TableCell className="text-content-secondary">
                        {fileSize(file.sizeBytes, i18n.language)}
                      </TableCell>
                      <TableCell className="text-content-secondary">
                        {new Date(file.createdAt).toLocaleDateString(i18n.language)}
                      </TableCell>
                      <TableCell>
                        {view === "pending" ? (
                          <PendingActions
                            file={file}
                            onRetried={() => cache.invalidateQueries({ queryKey: chatLibraryKey })}
                            onRemove={() => setConfirming([file])}
                          />
                        ) : (
                          <FileActions
                            file={file}
                            onDelete={() => setConfirming([file])}
                            onAddToProject={() => setProjectFiles([file])}
                            onRename={() => setRenaming(file)}
                            onFavorite={() => void change(file, { favorite: !file.favorite })}
                          />
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            ) : (
              <div className="flex flex-col gap-6">
                {groups.map((group) => (
                  <section key={group.label} aria-label={groupLabels[group.label]}>
                    <h2 className="mb-2 text-sm font-medium text-content-muted">
                      {groupLabels[group.label]}
                    </h2>
                    <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
                      {group.items.map((file) => (
                        <li
                          key={file.id}
                          className="flex flex-col gap-2 rounded-lg border border-border-default p-3"
                        >
                          <button
                            type="button"
                            className="flex aspect-square items-center justify-center overflow-hidden rounded-md bg-surface-sunken"
                            onClick={() => setPreview(libraryPreviewTarget(file))}
                            aria-label={ui("Xem trước {{name}}", { name: file.filename })}
                          >
                            {file.source === "IMAGE" ? (
                              <img
                                src={imageArtifactUrl(file.id)}
                                alt=""
                                loading="lazy"
                                className="size-full object-cover"
                              />
                            ) : (
                              categoryIcon(file.category)
                            )}
                          </button>
                          <span className="truncate text-sm" title={file.filename}>
                            {file.filename}
                          </span>
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-xs text-content-muted">
                              {fileSize(file.sizeBytes, i18n.language)}
                            </span>
                            <FileActions
                              file={file}
                              onDelete={() => setConfirming([file])}
                              onAddToProject={() => setProjectFiles([file])}
                              onRename={() => setRenaming(file)}
                              onFavorite={() => void change(file, { favorite: !file.favorite })}
                            />
                          </div>
                        </li>
                      ))}
                    </ul>
                  </section>
                ))}
              </div>
            ))}

          {(offset > 0 || page.data?.hasMore) && (
            <div className="flex items-center justify-between">
              <Button
                prominence="internal"
                disabled={offset === 0}
                onClick={() => showPage(Math.max(0, offset - LIBRARY_PAGE_SIZE))}
              >
                {ui("Trang trước")}
              </Button>
              <Button
                prominence="internal"
                disabled={!page.data?.hasMore}
                onClick={() => showPage(offset + LIBRARY_PAGE_SIZE)}
              >
                {ui("Trang sau")}
              </Button>
            </div>
          )}
        </LibraryDropZone>
      </SettingsLayout>

      {preview && <ChatFilePreviewModal target={preview} onClose={() => setPreview(undefined)} />}
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
        description={ui(
          "Tệp sẽ bị xoá khỏi mọi cuộc hội thoại và không thể khôi phục. Đã chọn {{count}} tệp.",
          { count: confirming?.length ?? 0 },
        )}
        confirmLabel={ui("Xoá")}
        pendingLabel={ui("Đang xoá…")}
        confirmTone="danger"
        onConfirm={() => removeAll(confirming ?? [])}
      />
    </AppShell>
  );
}

function FilterToggle({
  label,
  pressed,
  onToggle,
}: {
  label: string;
  pressed: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onToggle}
      className={cn(
        "h-7 rounded-full border px-3 text-sm transition-colors outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40",
        pressed
          ? "border-transparent bg-surface-accent text-content-on-accent"
          : "border-border-default text-content-secondary hover:text-content-primary",
      )}
    >
      {label}
    </button>
  );
}

/** What holds an upload, with a way to take it out of a Project; an assistant's files are edited on the assistant. */
function FileUsage({
  file,
  onRemoved,
}: {
  file: LibraryFile;
  onRemoved: (name: string) => Promise<void>;
}) {
  const ui = useAppTranslation();
  const [pending, setPending] = useState<string>();
  const [failed, setFailed] = useState(false);
  if (file.usedBy.length === 0) return null;
  return (
    <span className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-content-muted">
      <span>{ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}</span>
      {file.usedBy
        .filter((usage) => usage.kind === "PROJECT")
        .map((usage) => (
          <button
            key={usage.id}
            type="button"
            disabled={pending !== undefined}
            className="underline hover:text-content-primary disabled:opacity-50"
            onClick={async () => {
              setPending(usage.id);
              setFailed(false);
              try {
                await removeFromProject(usage.id, file.id, AbortSignal.timeout(30000));
                await onRemoved(usage.name);
              } catch {
                setFailed(true);
              } finally {
                setPending(undefined);
              }
            }}
          >
            {ui("Gỡ khỏi {{name}}", { name: usage.name })}
          </button>
        ))}
      {failed && <span role="alert">{ui("Không gỡ được. Hãy thử lại.")}</span>}
    </span>
  );
}

function statusLabel(file: LibraryFile, ui: ReturnType<typeof useAppTranslation>) {
  switch (file.status) {
    case "UPLOADING":
      return ui("Chưa xác nhận tải lên");
    case "PROCESSING":
      return ui("Đang xử lý…");
    case "FAILED":
      return file.errorCode === "UPLOAD_EXPIRED"
        ? ui("Tải lên hết hạn · hãy tải lại tệp")
        : ui("Xử lý lỗi");
    default:
      return ui("Sẵn sàng");
  }
}

/** An upload that is not usable yet can only be retried or removed; it has nothing to preview or attach. */
function PendingActions({
  file,
  onRetried,
  onRemove,
}: {
  file: LibraryFile;
  onRetried: () => Promise<unknown>;
  onRemove: () => void;
}) {
  const ui = useAppTranslation();
  const [busy, setBusy] = useState(false);
  return (
    <div className="flex items-center justify-end gap-1">
      {file.status === "FAILED" && file.errorCode !== "UPLOAD_EXPIRED" && (
        <Button
          size="sm"
          prominence="internal"
          pending={busy}
          onClick={async () => {
            setBusy(true);
            try {
              await retryChatFile({
                path: { fileId: file.id },
                headers: sameOriginMutationHeaders,
                signal: AbortSignal.timeout(30000),
                throwOnError: true,
              });
              await onRetried();
            } finally {
              setBusy(false);
            }
          }}
        >
          <RotateCcw className="size-4" aria-hidden="true" />
          {ui("Thử lại")}
        </Button>
      )}
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Gỡ bỏ {{name}}", { name: file.filename })}
        title={ui("Gỡ bỏ")}
        onClick={onRemove}
      >
        <Trash2 />
      </IconButton>
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

function FileActions({
  file,
  onDelete,
  onAddToProject,
  onRename,
  onFavorite,
}: {
  file: LibraryFile;
  onDelete: () => void;
  onAddToProject: () => void;
  onRename: () => void;
  onFavorite: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex items-center justify-end gap-1">
      <IconButton
        size="sm"
        prominence="internal"
        aria-pressed={file.favorite}
        aria-label={
          file.favorite
            ? ui("Bỏ yêu thích {{name}}", { name: file.filename })
            : ui("Đánh dấu yêu thích {{name}}", { name: file.filename })
        }
        onClick={onFavorite}
      >
        <Star className={file.favorite ? "fill-current text-status-warning-content" : undefined} />
      </IconButton>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Thao tác với {{name}}", { name: file.filename })}
          >
            <MoreHorizontal />
          </IconButton>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={onRename}>
            <Pencil />
            {ui("Đổi tên")}
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={onAddToProject}>
            <FolderPlus />
            {ui("Thêm vào dự án")}
          </DropdownMenuItem>
          {file.sessionId && (
            <DropdownMenuItem asChild>
              <Link to="/chat/$sessionId" params={{ sessionId: file.sessionId }}>
                <MessageSquare />
                {ui("Mở hội thoại gốc")}
              </Link>
            </DropdownMenuItem>
          )}
          <DropdownMenuItem asChild>
            <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
              <Download />
              {ui("Tải về")}
            </a>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem variant="destructive" disabled={!file.deletable} onSelect={onDelete}>
            <Trash2 />
            {file.deletable
              ? ui("Xoá")
              : ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
