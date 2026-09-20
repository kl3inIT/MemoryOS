import { useDeferredValue, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import {
  Download,
  FileSpreadsheet,
  FileText,
  Image as ImageIcon,
  LayoutGrid,
  List,
  MessageSquare,
  Presentation,
  Search,
  Trash2,
} from "lucide-react";
import { AppShell } from "@/components/app-shell/app-shell";
import { Button } from "@/components/ui/button";
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
import { fileSize } from "./chat-code";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import { downloadUrl, type PreviewTarget } from "./chat-file-preview";
import { imageArtifactUrl } from "./chat-image";
import {
  chatLibraryKey,
  deleteLibraryFile,
  groupByDay,
  libraryPreviewTarget,
  loadLibrary,
  refusedBy,
  usageLabel,
  LIBRARY_PAGE_SIZE,
  type LibraryCategory,
  type LibraryFile,
  type LibrarySort,
  type LibrarySource,
} from "./chat-library";

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
  const [layout, setLayout] = useState<"table" | "grid">("table");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const [preview, setPreview] = useState<PreviewTarget>();
  const [confirming, setConfirming] = useState<LibraryFile[]>();
  const [refusals, setRefusals] = useState<string[]>([]);

  /** A selection belongs to the page it was made on, so leaving that page drops it. */
  const showPage = (next: number) => {
    setOffset(next);
    setSelected([]);
  };
  const showFirstPage = () => showPage(0);

  const filter = useMemo(
    () => ({ query, sources, categories, sort }),
    [query, sources, categories, sort],
  );
  const page = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, filter, offset],
    queryFn: ({ signal }) => loadLibrary(filter, offset, signal),
  });
  const files = page.data?.items ?? [];
  const groups = groupByDay(files);
  const selectable = files.filter((file) => file.deletable);
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
            <Tabs value={layout} onValueChange={(value) => setLayout(value as "table" | "grid")}>
              <TabsList aria-label={ui("Cách hiển thị")}>
                <TabsTrigger value="table" aria-label={ui("Dạng bảng")}>
                  <List className="size-4" />
                </TabsTrigger>
                <TabsTrigger value="grid" aria-label={ui("Dạng lưới")}>
                  <LayoutGrid className="size-4" />
                </TabsTrigger>
              </TabsList>
            </Tabs>
          }
        />

        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-3">
            <div className="relative min-w-0 flex-1">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted" />
              <Input
                value={search}
                onChange={(event) => fromTheFirstPage(setSearch)(event.target.value)}
                placeholder={ui("Tìm theo tên tệp")}
                aria-label={ui("Tìm theo tên tệp")}
                maxLength={200}
                className="pl-9"
              />
            </div>
            <Select
              className="w-52"
              aria-label={ui("Sắp xếp")}
              value={sort}
              onChange={(event) => fromTheFirstPage(setSort)(event.target.value as LibrarySort)}
            >
              <option value="NEWEST">{ui("Mới nhất")}</option>
              <option value="OLDEST">{ui("Cũ nhất")}</option>
              <option value="LARGEST">{ui("Dung lượng giảm dần")}</option>
              <option value="SMALLEST">{ui("Dung lượng tăng dần")}</option>
            </Select>
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
          </div>
        </div>

        {selected.length > 0 && (
          <div className="flex items-center gap-3 rounded-lg border border-border-default bg-surface-raised px-4 py-2">
            <span className="text-sm">
              {ui("Đã chọn {{count}} tệp", { count: selected.length })}
            </span>
            <Button size="sm" tone="danger" onClick={() => setConfirming(chosen)}>
              <Trash2 className="size-4" />
              {ui("Xoá")}
            </Button>
            <Button size="sm" prominence="internal" onClick={() => setSelected([])}>
              {ui("Bỏ chọn")}
            </Button>
          </div>
        )}
        {refusals.length > 0 && (
          <ul role="alert" className="flex flex-col gap-1 text-sm text-content-danger">
            {refusals.map((refusal) => (
              <li key={refusal}>{refusal}</li>
            ))}
          </ul>
        )}

        {page.isPending && <p role="status">{ui("Đang tải thư viện…")}</p>}
        {page.isError && (
          <p role="alert">
            {ui("Không tải được thư viện.")}{" "}
            <Button prominence="internal" size="sm" onClick={() => void page.refetch()}>
              {ui("Thử lại")}
            </Button>
          </p>
        )}
        {page.isSuccess && files.length === 0 && (
          <p role="status" className="text-content-muted">
            {query || sources.length > 0 || categories.length > 0
              ? ui("Không có tệp nào khớp bộ lọc.")
              : ui("Chưa có tệp nào. Tệp bạn tải lên hoặc Chat tạo ra sẽ xuất hiện ở đây.")}
          </p>
        )}

        {files.length > 0 &&
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
                  <TableHead>{ui("Nguồn")}</TableHead>
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
                        disabled={!file.deletable}
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
                      {usageLabel(file) && (
                        <span className="mt-0.5 block text-xs text-content-muted">
                          {ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="text-content-secondary">
                      {sourceLabels[file.source]}
                    </TableCell>
                    <TableCell className="text-content-secondary">
                      {fileSize(file.sizeBytes, i18n.language)}
                    </TableCell>
                    <TableCell className="text-content-secondary">
                      {new Date(file.createdAt).toLocaleDateString(i18n.language)}
                    </TableCell>
                    <TableCell>
                      <FileActions file={file} onDelete={() => setConfirming([file])} />
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
                          <FileActions file={file} onDelete={() => setConfirming([file])} />
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
      </SettingsLayout>

      {preview && <ChatFilePreviewModal target={preview} onClose={() => setPreview(undefined)} />}
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

function FileActions({ file, onDelete }: { file: LibraryFile; onDelete: () => void }) {
  const ui = useAppTranslation();
  return (
    <div className="flex items-center justify-end gap-1">
      {file.sessionId && (
        <IconButton
          size="sm"
          prominence="internal"
          asChild
          aria-label={ui("Mở hội thoại gốc")}
          title={file.sessionTitle ?? undefined}
        >
          <Link to="/chat/$sessionId" params={{ sessionId: file.sessionId }}>
            <MessageSquare />
          </Link>
        </IconButton>
      )}
      <IconButton size="sm" prominence="internal" asChild aria-label={ui("Tải về")}>
        <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
          <Download />
        </a>
      </IconButton>
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Xoá {{name}}", { name: file.filename })}
        disabled={!file.deletable}
        title={
          file.deletable ? undefined : ui("Đang dùng trong {{name}}", { name: usageLabel(file) })
        }
        onClick={onDelete}
      >
        <Trash2 />
      </IconButton>
    </div>
  );
}
