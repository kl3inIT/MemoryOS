import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Item, ItemActions, ItemContent, ItemDescription, ItemMedia } from "@/components/ui/item";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { IconButton } from "@/components/ui/icon-button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Separator } from "@/components/ui/separator";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { DocumentKindIcon } from "@/features/documents/document-source-icon";
import { useDebouncedValue } from "@/hooks/use-debounced-value";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { actionErrorText } from "@/lib/action-errors";
import {
  listChatLibraryOptions,
  listChatLibraryQueryKey,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { ChatAddToProjectDialog } from "@/features/chat/projects/chat-add-to-project";
import { fileSize } from "@/lib/file-size";
import { fileIdFromReference } from "@/features/library/files";
import { ChatFilePreviewModal } from "@/features/library/file-preview-modal";
import { downloadUrl, type PreviewTarget } from "@/features/library/file-preview";
import {
  invalidateLibrary,
  deleteLibraryFile,
  LIBRARY_PAGE_SIZE,
  libraryPreviewTarget,
  libraryThumbnailUrl,
  libraryUpload,
  refusedBy,
  usageLabel,
  type LibraryCategory,
  type LibraryFile,
} from "@/features/library/library";
import { LibraryCategoryFilter } from "@/features/library/library-toolbar";
import { composerAttachment } from "@/features/chat/composer/use-composer-file-selection";

/** A question admits at most this many attachments. */
const ATTACHMENT_LIMIT = 20;

type Notice = { tone: "info" | "danger"; text: string };

/**
 * One conversation's files and what can be done with them: the filtered list, the count on the button, attaching a
 * file to the next question and deleting one. A turn that finishes may have produced files, so the lists refresh.
 */
function useSessionFiles(
  sessionId: string,
  filter: { query: string; categories: LibraryCategory[] },
  onNotice: (notice: Notice | undefined) => void,
) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const aui = useAui();
  const files = useQuery(
    listChatLibraryOptions({
      query: {
        query: filter.query,
        sources: [],
        categories: filter.categories,
        sessionId,
        sort: "NEWEST",
        offset: 0,
        limit: LIBRARY_PAGE_SIZE,
      },
    }),
  );
  // The count on the button is the whole conversation, not the filtered list inside the sheet.
  const total = useQuery(
    listChatLibraryOptions({
      query: { sessionId, sort: "NEWEST", offset: 0, limit: LIBRARY_PAGE_SIZE },
    }),
  );
  // The library page and this panel both read the library; a change here refreshes both.
  const refreshLibrary = () =>
    Promise.all([
      cache.invalidateQueries({ queryKey: listChatLibraryQueryKey() }),
      invalidateLibrary(cache),
    ]);

  // The thread's run is an external system: a finished turn may have produced files.
  const running = useAuiState((state) => state.thread.isRunning);
  const wasRunning = useRef(running);
  useEffect(() => {
    if (wasRunning.current && !running) void refreshLibrary();
    wasRunning.current = running;
  });

  const attach = useMutation({
    mutationFn: async (file: LibraryFile) => {
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
      if (attached.length >= ATTACHMENT_LIMIT) return false;
      const upload = await libraryUpload(file, AbortSignal.timeout(120_000));
      if (!attached.includes(upload.id)) await composer.addAttachment(composerAttachment(upload));
      return true;
    },
    onMutate: () => onNotice(undefined),
    onSuccess: (attached, file) =>
      onNotice(
        attached
          ? {
              tone: "info",
              text: ui("Đã đính kèm {{name}} vào câu hỏi tiếp theo.", { name: file.filename }),
            }
          : { tone: "danger", text: ui("Mỗi câu hỏi đính kèm tối đa 20 tệp.") },
      ),
    onError: (failure) => onNotice({ tone: "danger", text: actionErrorText(failure) }),
  });

  const remove = useMutation({
    mutationFn: (file: LibraryFile) => deleteLibraryFile(file, AbortSignal.timeout(30000)),
    onSuccess: () => onNotice(undefined),
    onError: (failure) => {
      const holders = refusedBy(failure);
      onNotice({
        tone: "danger",
        text:
          holders.length > 0
            ? ui("Đang dùng trong {{name}}", { name: holders.join(", ") })
            : actionErrorText(failure),
      });
    },
    onSettled: refreshLibrary,
  });
  return { files, total, attach, remove };
}

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
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const query = useDebouncedValue(search.trim(), 250);
  const [categories, setCategories] = useState<LibraryCategory[]>([]);
  const [preview, setPreview] = useState<PreviewTarget>();
  const [confirming, setConfirming] = useState<LibraryFile>();
  const [projectFiles, setProjectFiles] = useState<LibraryFile[]>();
  const [notice, setNotice] = useState<Notice>();
  const { files, total, attach, remove } = useSessionFiles(
    sessionId,
    { query, categories },
    setNotice,
  );
  const items = files.data?.items ?? [];
  const count = total.data?.totalCount ?? 0;
  const filtered = query !== "" || categories.length > 0;

  const show = async (file: LibraryFile) => {
    if (!file.messageId || !onShowMessage) return;
    setOpen(false);
    const found = await onShowMessage(file.messageId).catch(() => false);
    if (!found) {
      setOpen(true);
      setNotice({ tone: "danger", text: ui("Không tìm thấy tin nhắn chứa tệp này.") });
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
            className="absolute -top-1 -right-1 min-w-4 rounded-full bg-primary px-1 text-xs leading-4 font-medium text-primary-foreground"
          >
            {count > 99 ? "99+" : count}
          </span>
        )}
      </IconButton>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right">
          <SheetHeader>
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
          <Separator />
          <div className="flex flex-col gap-2 px-4">
            <InputGroup>
              <InputGroupAddon>
                <Search aria-hidden="true" />
              </InputGroupAddon>
              <InputGroupInput
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={ui("Tìm theo tên tệp")}
                aria-label={ui("Tìm theo tên tệp")}
                maxLength={200}
              />
            </InputGroup>
            <div className="flex items-center justify-between gap-2">
              <LibraryCategoryFilter categories={categories} onCategories={setCategories} />
              <span className="font-secondary-body tabular-nums text-content-muted">
                {ui("{{count}} tệp", { count: items.length })}
              </span>
            </div>
          </div>
          <Separator />
          <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto px-4 pb-4">
            {notice && (
              <Alert variant={notice.tone === "danger" ? "destructive" : "success"}>
                <AlertTitle>{notice.text}</AlertTitle>
              </Alert>
            )}
            {files.isError && (
              <Alert variant="destructive">
                <AlertTitle>{ui("Không tải được thư viện.")}</AlertTitle>
              </Alert>
            )}
            {files.isSuccess && items.length === 0 && (
              <Empty>
                <EmptyHeader>
                  <EmptyMedia variant="icon">
                    <Paperclip />
                  </EmptyMedia>
                  <EmptyTitle>
                    {filtered ? ui("Không có tệp nào khớp") : ui("Hội thoại này chưa có tệp nào")}
                  </EmptyTitle>
                  <EmptyDescription>
                    {filtered
                      ? ui("Hãy bỏ một vài bộ lọc hoặc đổi từ khoá tìm kiếm.")
                      : ui("Tệp bạn đính kèm và tệp Chat tạo ra sẽ xuất hiện ở đây.")}
                  </EmptyDescription>
                </EmptyHeader>
              </Empty>
            )}
            {items.map((file) => (
              <SessionFileRow
                key={`${file.source}:${file.id}`}
                file={file}
                attaching={attach.isPending ? attach.variables?.id : undefined}
                onPreview={() => setPreview(libraryPreviewTarget(file))}
                onAttach={() => attach.mutate(file)}
                onShow={file.messageId && onShowMessage ? () => void show(file) : undefined}
                onAddToProject={() => setProjectFiles([file])}
                onDelete={() => setConfirming(file)}
              />
            ))}
          </div>
        </SheetContent>
      </Sheet>
      {preview && (
        <ChatFilePreviewModal
          target={preview}
          siblings={items.map(libraryPreviewTarget)}
          onClose={() => setPreview(undefined)}
        />
      )}
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
          if (file) await remove.mutateAsync(file).catch(() => undefined);
        }}
      />
    </>
  );
}

/** One file of the conversation with its preview, attach action and menu. */
function SessionFileRow({
  file,
  attaching,
  onPreview,
  onAttach,
  onShow,
  onAddToProject,
  onDelete,
}: {
  file: LibraryFile;
  /** The file being attached, if any; every attach button waits for it. */
  attaching?: string;
  onPreview: () => void;
  onAttach: () => void;
  onShow?: () => void;
  onAddToProject: () => void;
  onDelete: () => void;
}) {
  const ui = useAppTranslation();
  const sourceLabels = {
    UPLOAD: ui("Đã tải lên"),
    GENERATED: ui("Do mã tạo"),
    IMAGE: ui("Ảnh AI"),
  } as const;
  const thumbnail = libraryThumbnailUrl(file);
  return (
    <Item variant="outline" size="sm">
      <ItemMedia variant={thumbnail ? "image" : "icon"}>
        <button
          type="button"
          className="grid size-full place-items-center overflow-hidden rounded-sm bg-surface-sunken outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40"
          aria-label={ui("Xem trước {{name}}", { name: file.filename })}
          onClick={onPreview}
        >
          {thumbnail ? (
            <img src={thumbnail} alt="" loading="lazy" decoding="async" />
          ) : (
            <DocumentKindIcon
              mediaType={file.mediaType}
              filename={file.filename}
              className="size-4"
            />
          )}
        </button>
      </ItemMedia>
      <ItemContent className="min-w-0">
        <button
          type="button"
          className="min-w-0 truncate text-left font-main-ui-action hover:underline"
          onClick={onPreview}
        >
          {file.filename}
        </button>
        <ItemDescription>
          {[sourceLabels[file.source], fileSize(file.sizeBytes, i18n.language)].join(" · ")}
        </ItemDescription>
        {usageLabel(file) && (
          <Badge variant="outline" className="mt-0.5 w-fit">
            {ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}
          </Badge>
        )}
      </ItemContent>
      <ItemActions>
        {/* Revealed while the row is hovered or focused on wide screens; always shown on touch widths. */}
        <div className="flex items-center gap-1 opacity-100 transition-opacity md:opacity-0 md:group-hover/item:opacity-100 md:group-focus-within/item:opacity-100">
          <IconButton
            size="sm"
            prominence="internal"
            aria-label={ui("Đính kèm {{name}} vào câu hỏi", { name: file.filename })}
            title={ui("Đính kèm vào câu hỏi tiếp theo")}
            pending={attaching === file.id}
            disabled={attaching !== undefined}
            onClick={onAttach}
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
              <DropdownMenuGroup>
                {onShow && (
                  <DropdownMenuItem onSelect={onShow}>
                    <LocateFixed />
                    {ui("Xem trong hội thoại")}
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem onSelect={onAddToProject}>
                  <FolderPlus />
                  {ui("Thêm vào dự án")}
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
                    <Download />
                    {ui("Tải về")}
                  </a>
                </DropdownMenuItem>
              </DropdownMenuGroup>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuItem
                  variant="destructive"
                  disabled={!file.deletable}
                  onSelect={onDelete}
                >
                  <Trash2 />
                  {ui("Xoá")}
                </DropdownMenuItem>
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </ItemActions>
    </Item>
  );
}
