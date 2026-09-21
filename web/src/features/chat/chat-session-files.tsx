import { useDeferredValue, useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useAui, useAuiState } from "@assistant-ui/react";
import {
  Download,
  FolderPlus,
  LocateFixed,
  MoreHorizontal,
  Paperclip,
  Search,
  Trash2,
} from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { DocumentKindIcon } from "@/features/search/document-source-icon";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { cn } from "@/lib/utils";
import { chatActionError } from "./chat-action-utils";
import { ChatAddToProjectDialog } from "./chat-add-to-project";
import { fileSize } from "./chat-code";
import { fileIdFromReference } from "./chat-files";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import { downloadUrl, type PreviewTarget } from "./chat-file-preview";
import { imageArtifactUrl } from "./chat-image";
import {
  chatLibraryKey,
  deleteLibraryFile,
  libraryPreviewTarget,
  libraryUpload,
  loadLibrary,
  refusedBy,
  usageLabel,
  type LibraryCategory,
  type LibraryFile,
} from "./chat-library";
import { composerAttachment } from "./use-composer-file-selection";

const CATEGORIES: LibraryCategory[] = ["DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER"];

/**
 * "Files in this conversation" (MEM-144): the library list narrowed to one conversation, so a person can find
 * what they attached or what a run produced without scrolling the transcript. MEM-152 makes each row actionable:
 * attach it to the next question, jump to the message it belongs to, or add it to a Project.
 */
export function ChatSessionFiles({
  sessionId,
  onShowMessage,
}: {
  sessionId: string;
  /** Scrolls the transcript to a message, switching versions when it is on another branch. */
  onShowMessage?: (messageId: string) => Promise<boolean>;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const aui = useAui();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const [categories, setCategories] = useState<LibraryCategory[]>([]);
  const [preview, setPreview] = useState<PreviewTarget>();
  const [confirming, setConfirming] = useState<LibraryFile>();
  const [projectFiles, setProjectFiles] = useState<LibraryFile[]>();
  const [notice, setNotice] = useState<{ tone: "info" | "danger"; text: string }>();
  const [working, setWorking] = useState<string>();

  const filter = { query, sources: [], categories, sort: "NEWEST" as const, sessionId };
  const files = useQuery({
    queryKey: [
      ...chatLibraryKey,
      actorId,
      authorizationVersion,
      "session",
      sessionId,
      query,
      categories,
    ],
    queryFn: ({ signal }) => loadLibrary(filter, 0, signal),
  });
  const items = files.data?.items ?? [];
  // The count on the button is the whole conversation, not the filtered list inside the sheet.
  const total = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "session", sessionId],
    queryFn: ({ signal }) =>
      loadLibrary({ query: "", sources: [], categories: [], sort: "NEWEST", sessionId }, 0, signal),
  });
  const count = total.data?.totalCount ?? 0;

  // A finished turn may have produced files; refresh instead of waiting for a reload.
  const running = useAuiState((state) => state.thread.isRunning);
  const wasRunning = useRef(running);
  useEffect(() => {
    if (wasRunning.current && !running) void cache.invalidateQueries({ queryKey: chatLibraryKey });
    wasRunning.current = running;
  }, [running, cache]);

  const categoryLabels: Record<LibraryCategory, string> = {
    DOCUMENT: ui("Tài liệu"),
    SPREADSHEET: ui("Bảng tính"),
    IMAGE: ui("Ảnh"),
    PRESENTATION: ui("Trình chiếu"),
    OTHER: ui("Khác"),
  };
  const sourceLabels = {
    UPLOAD: ui("Đã tải lên"),
    GENERATED: ui("Do mã tạo"),
    IMAGE: ui("Ảnh AI"),
  } as const;

  const attach = async (file: LibraryFile) => {
    setWorking(file.id);
    setNotice(undefined);
    try {
      const composer = aui.thread.composer();
      const attached = composer
        .getState()
        .attachments.flatMap((attachment) =>
          (attachment.content ?? []).flatMap((part) =>
            part.type === "file" && typeof part.data === "string"
              ? [fileIdFromReference(part.data)]
              : [],
          ),
        );
      if (attached.length >= 20) {
        setNotice({ tone: "danger", text: ui("Mỗi câu hỏi đính kèm tối đa 20 tệp.") });
        return;
      }
      const upload = await libraryUpload(file, AbortSignal.timeout(120_000));
      if (!attached.includes(upload.id)) await composer.addAttachment(composerAttachment(upload));
      setNotice({
        tone: "info",
        text: ui("Đã đính kèm {{name}} vào câu hỏi tiếp theo.", { name: file.filename }),
      });
    } catch (failure) {
      setNotice({ tone: "danger", text: chatActionError(failure) });
    } finally {
      setWorking(undefined);
    }
  };

  const show = async (file: LibraryFile) => {
    if (!file.messageId || !onShowMessage) return;
    setOpen(false);
    const found = await onShowMessage(file.messageId).catch(() => false);
    if (!found) {
      setOpen(true);
      setNotice({ tone: "danger", text: ui("Không tìm thấy tin nhắn chứa tệp này.") });
    }
  };

  const remove = async (file: LibraryFile) => {
    try {
      await deleteLibraryFile(file, AbortSignal.timeout(30000));
      setNotice(undefined);
    } catch (failure) {
      const holders = refusedBy(failure);
      setNotice({
        tone: "danger",
        text:
          holders.length > 0
            ? ui("Đang dùng trong {{name}}", { name: holders.join(", ") })
            : chatActionError(failure),
      });
    } finally {
      await cache.invalidateQueries({ queryKey: chatLibraryKey });
    }
  };

  return (
    <>
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={
          count > 0 ? ui("Tệp trong hội thoại ({{count}})", { count }) : ui("Tệp trong hội thoại")
        }
        title={ui("Tệp trong hội thoại")}
        onClick={() => setOpen(true)}
        className="relative"
      >
        <Paperclip />
        {count > 0 && (
          <span
            aria-hidden="true"
            className="absolute -top-1 -right-1 min-w-4 rounded-full bg-surface-accent px-1 text-[10px] leading-4 font-medium text-content-on-accent"
          >
            {count > 99 ? "99+" : count}
          </span>
        )}
      </IconButton>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="gap-0">
          <SheetHeader className="border-b border-border-subtle pr-12">
            <SheetTitle>{ui("Tệp trong hội thoại")}</SheetTitle>
            <SheetDescription>
              {total.data
                ? ui("{{count}} tệp · {{size}}", {
                    count: total.data.totalCount,
                    size: fileSize(total.data.totalBytes, i18n.language),
                  })
                : ui("Đang tải thư viện…")}
            </SheetDescription>
          </SheetHeader>
          <div className="flex flex-col gap-2 border-b border-border-subtle p-4">
            <div className="relative">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted" />
              <Input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={ui("Tìm theo tên tệp")}
                aria-label={ui("Tìm theo tên tệp")}
                maxLength={200}
                className="pl-9"
              />
            </div>
            <div className="flex flex-wrap gap-1.5" role="group" aria-label={ui("Lọc tệp")}>
              {CATEGORIES.map((category) => (
                <button
                  key={category}
                  type="button"
                  aria-pressed={categories.includes(category)}
                  onClick={() =>
                    setCategories(
                      categories.includes(category)
                        ? categories.filter((item) => item !== category)
                        : [...categories, category],
                    )
                  }
                  className={cn(
                    "h-6 rounded-full border px-2.5 text-xs transition-colors outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40",
                    categories.includes(category)
                      ? "border-transparent bg-surface-accent text-content-on-accent"
                      : "border-border-default text-content-secondary hover:text-content-primary",
                  )}
                >
                  {categoryLabels[category]}
                </button>
              ))}
            </div>
          </div>
          <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4">
            {notice && (
              <p
                role={notice.tone === "danger" ? "alert" : "status"}
                className={cn(
                  "text-sm",
                  notice.tone === "danger" ? "text-content-danger" : "text-content-secondary",
                )}
              >
                {notice.text}
              </p>
            )}
            {files.isError && <p role="alert">{ui("Không tải được thư viện.")}</p>}
            {files.isSuccess && items.length === 0 && (
              <p role="status" className="text-content-muted">
                {query || categories.length > 0
                  ? ui("Không có tệp nào khớp bộ lọc.")
                  : ui("Hội thoại này chưa có tệp nào.")}
              </p>
            )}
            {items.map((file) => (
              <div
                key={`${file.source}:${file.id}`}
                className="flex items-center gap-3 rounded-lg border border-border-default px-3 py-2"
              >
                <button
                  type="button"
                  className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-md bg-surface-sunken"
                  aria-label={ui("Xem trước {{name}}", { name: file.filename })}
                  onClick={() => setPreview(libraryPreviewTarget(file))}
                >
                  {file.source === "IMAGE" ? (
                    <img
                      src={imageArtifactUrl(file.id)}
                      alt=""
                      loading="lazy"
                      className="size-full object-cover"
                    />
                  ) : (
                    <DocumentKindIcon
                      mediaType={file.mediaType}
                      filename={file.filename}
                      className="size-4"
                    />
                  )}
                </button>
                <button
                  type="button"
                  className="min-w-0 flex-1 text-left hover:underline"
                  onClick={() => setPreview(libraryPreviewTarget(file))}
                >
                  <span className="block truncate text-sm">{file.filename}</span>
                  <span className="block truncate text-xs text-content-muted">
                    {[sourceLabels[file.source], fileSize(file.sizeBytes, i18n.language)].join(
                      " · ",
                    )}
                  </span>
                  {usageLabel(file) && (
                    <span className="block truncate text-xs text-content-muted">
                      {ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}
                    </span>
                  )}
                </button>
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Đính kèm {{name}} vào câu hỏi", { name: file.filename })}
                  title={ui("Đính kèm vào câu hỏi tiếp theo")}
                  pending={working === file.id}
                  disabled={working !== undefined}
                  onClick={() => void attach(file)}
                >
                  <Paperclip />
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
                    {file.messageId && onShowMessage && (
                      <DropdownMenuItem onSelect={() => void show(file)}>
                        <LocateFixed />
                        {ui("Xem trong hội thoại")}
                      </DropdownMenuItem>
                    )}
                    <DropdownMenuItem onSelect={() => setProjectFiles([file])}>
                      <FolderPlus />
                      {ui("Thêm vào dự án")}
                    </DropdownMenuItem>
                    <DropdownMenuItem asChild>
                      <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
                        <Download />
                        {ui("Tải về")}
                      </a>
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                      variant="destructive"
                      disabled={!file.deletable}
                      onSelect={() => setConfirming(file)}
                    >
                      <Trash2 />
                      {ui("Xoá")}
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            ))}
          </div>
        </SheetContent>
      </Sheet>
      {preview && <ChatFilePreviewModal target={preview} onClose={() => setPreview(undefined)} />}
      <ChatAddToProjectDialog
        files={projectFiles}
        onOpenChange={(next) => !next && setProjectFiles(undefined)}
        onAdded={(name) =>
          setNotice({ tone: "info", text: ui("Đã thêm vào dự án {{name}}.", { name }) })
        }
      />
      <ConfirmDialog
        open={confirming !== undefined}
        onOpenChange={(next) => !next && setConfirming(undefined)}
        title={ui("Xoá tệp?")}
        description={ui(
          "Tệp sẽ bị xoá khỏi mọi cuộc hội thoại và không thể khôi phục. Đã chọn {{count}} tệp.",
          {
            count: 1,
          },
        )}
        confirmLabel={ui("Xoá")}
        pendingLabel={ui("Đang xoá…")}
        confirmTone="danger"
        onConfirm={async () => {
          const file = confirming;
          setConfirming(undefined);
          if (file) await remove(file);
        }}
      />
    </>
  );
}
