import { DocumentKindIcon } from "@/features/documents/document-source-icon";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { CircleAlert, Paperclip, SearchX, Trash2, Upload } from "lucide-react";
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover";
import { FormDialog } from "@/components/composites/form-dialog";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Spinner } from "@/components/ui/spinner";
import {
  deleteChatFileMutation,
  finalizeChatFileUploadMutation,
  getChatFileOptions,
  listChatFilesOptions,
  listChatFilesQueryKey,
  retryChatFileMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { isNotFound } from "@/lib/api";
import { chatAttachmentProblem, chatFileOf, uploadChatFile, type ChatFile } from "./files";
import { useProblemMessage } from "@/lib/use-problem-message";

export function ChatFilePicker({
  trigger,
  uploadAction,
  ...props
}: {
  selected: string[];
  onSelect: (ids: string[], files: ChatFile[]) => void;
  disabled?: boolean;
  trigger?: ReactNode;
  uploadAction?: ReactNode;
}) {
  const ui = useAppTranslation();

  const [open, setOpen] = useState(false);
  const [all, setAll] = useState(false);
  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          {trigger ?? (
            <IconButton
              type="button"
              size="sm"
              prominence="internal"
              disabled={props.disabled}
              aria-label={ui("Đính kèm tệp")}
              title={ui("Đính kèm tệp")}
            >
              <Paperclip />
            </IconButton>
          )}
        </PopoverTrigger>
        <PopoverContent align="start" side="top" className="w-80 max-w-[calc(100vw-2rem)]">
          {open && (
            <ChatFilePickerContent
              {...props}
              compact
              uploadAction={uploadAction}
              onMore={() => {
                setOpen(false);
                setAll(true);
              }}
              onSelect={(ids, files) => {
                props.onSelect(ids, files);
                setOpen(false);
              }}
            />
          )}
        </PopoverContent>
      </Popover>
      <ChatRecentFilesDialog
        {...props}
        uploadAction={uploadAction}
        open={all}
        onOpenChange={setAll}
      />
    </>
  );
}

/** All recent files in a dialog; mounted only while open. */
function ChatRecentFilesDialog({
  open,
  onOpenChange,
  ...props
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  selected: string[];
  onSelect: (ids: string[], files: ChatFile[]) => void;
  disabled?: boolean;
  uploadAction?: ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <FormDialog
      title={ui("Tệp gần đây")}
      description={ui("Chọn lại tệp của bạn để sử dụng. Chỉ tệp đã xử lý xong mới được chọn.")}
      open={open}
      onOpenChange={onOpenChange}
    >
      {open && <ChatFilePickerContent {...props} />}
    </FormDialog>
  );
}

function fileStatusLabel(file: ChatFile, ui: ReturnType<typeof useAppTranslation>) {
  switch (file.status) {
    case "READY":
      return file.searchReady === false ? ui("Đọc được · Chưa sẵn sàng tìm kiếm") : undefined;
    case "PROCESSING":
      return ui("Đang xử lý…");
    case "UPLOADING":
      return ui("Chưa xác nhận upload");
    case "FAILED":
      return file.errorCode === "UPLOAD_EXPIRED"
        ? ui("Upload hết hạn · Chọn file để tải lại")
        : ui("Xử lý lỗi");
    default:
      return ui("Đã xóa");
  }
}

/** The file's leading icon carries its status: a spinner while pending, a warning on failure. */
function FileStatusIcon({ file }: { file: ChatFile }) {
  const ui = useAppTranslation();
  const label = fileStatusLabel(file, ui);
  if (!label)
    return (
      <DocumentKindIcon
        mediaType={file.mediaType}
        filename={file.filename}
        className="size-4 shrink-0"
      />
    );
  const icon =
    file.status === "PROCESSING" || file.status === "UPLOADING" ? (
      <span className="shrink-0 text-content-muted">
        <Spinner />
      </span>
    ) : file.status === "FAILED" ? (
      <CircleAlert aria-hidden="true" className="size-4 shrink-0 text-status-danger-content" />
    ) : file.status === "READY" ? (
      <SearchX aria-hidden="true" className="size-4 shrink-0 text-content-muted" />
    ) : (
      <Trash2 aria-hidden="true" className="size-4 shrink-0 text-content-muted" />
    );
  return (
    <span role="img" aria-label={label} title={label} className="inline-flex">
      {icon}
    </span>
  );
}

/** How many recent files one page holds. */
const recentPage = 30;
/** How many files one message may carry. */
const selectionLimit = 20;

/**
 * The caller's recent uploads, one page at a time, with every selected file that is not on that page read on
 * its own: the selection must show what it holds, and a file that is gone is named so it can be dropped.
 */
function useRecentFiles(offset: number, selected: readonly string[]) {
  const recent = useQuery({
    ...listChatFilesOptions({ query: { offset, limit: recentPage } }),
    select: (views) => views.map(chatFileOf),
    refetchInterval: (query) =>
      query.state.data?.some((file) => file.status === "PROCESSING") ? 1500 : false,
  });
  const missing = recent.data
    ? selected.filter((id) => !recent.data.some((file) => file.id === id))
    : [];
  const others = useQueries({
    queries: missing.map((fileId) => ({
      ...getChatFileOptions({ path: { fileId } }),
      select: chatFileOf,
      retry: false,
    })),
  });
  const failure = others.find((other) => other.isError && !isNotFound(other.error));
  return {
    entries: [
      ...others.flatMap((other) => (other.data ? [other.data] : [])).reverse(),
      ...(recent.data ?? []),
    ],
    /** How many files the page itself held, which says whether another page follows. */
    pageSize: recent.data?.length ?? 0,
    unavailable: missing.filter((_, index) => isNotFound(others[index]?.error)),
    isPending: recent.isPending || others.some((other) => other.isPending),
    isError: recent.isError || failure !== undefined,
    loaded: recent.isSuccess,
    refetch: () => recent.refetch(),
  };
}

/** `uploadAction` replaces the built-in server upload row; `null` hides it. */
export function ChatFilePickerContent({
  selected,
  onSelect,
  disabled = false,
  compact = false,
  onMore,
  moreLabel,
  uploadAction,
}: {
  selected: string[];
  onSelect: (ids: string[], files: ChatFile[]) => void;
  disabled?: boolean;
  compact?: boolean;
  onMore?: () => void;
  /** The compact list's way to the full list; the composer opens the whole library instead. */
  moreLabel?: string;
  uploadAction?: ReactNode;
}) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const problemMessage = useProblemMessage();
  const [offset, setOffset] = useState(0);
  const files = useRecentFiles(offset, selected);
  const refresh = () => cache.invalidateQueries({ queryKey: listChatFilesQueryKey() });
  const [progress, setProgress] = useState(0);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const upload = useMutation({
    mutationFn: async (file: File) => {
      controller.current?.abort();
      const running = new AbortController();
      controller.current = running;
      setProgress(0);
      try {
        return await uploadChatFile(
          file,
          crypto.randomUUID(),
          AbortSignal.any([running.signal, AbortSignal.timeout(30 * 60_000)]),
          setProgress,
        );
      } catch (cause) {
        // An upload replaced by the next one, or left behind with the picker, is not a failure to show.
        if (running.signal.aborted) return undefined;
        throw cause;
      }
    },
    onSuccess: refresh,
  });
  const retry = useMutation({ ...retryChatFileMutation(), onSuccess: refresh });
  const finalize = useMutation({ ...finalizeChatFileUploadMutation(), onSuccess: refresh });
  const remove = useMutation({ ...deleteChatFileMutation(), onSuccess: refresh });
  const acting = retry.isPending || finalize.isPending;
  const actionFailed = retry.isError || finalize.isError;
  const uploadError = upload.isError ? chatAttachmentProblem(upload.error) : undefined;

  function select(ids: string[]) {
    onSelect(
      ids,
      ids.flatMap((id) => files.entries.find((entry) => entry.id === id) ?? []),
    );
  }
  const shown = compact ? files.entries.slice(0, 3) : files.entries;
  return (
    <fieldset
      disabled={disabled || upload.isPending || acting}
      className="flex min-w-0 flex-col gap-3"
    >
      {uploadAction !== undefined ? (
        uploadAction
      ) : (
        <label className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 text-sm hover:bg-surface-sunken focus-within:ring-2">
          <Upload className="size-4" /> {ui("Tải tệp lên")}
          <input
            type="file"
            aria-label={ui("Tải tệp lên")}
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (file) upload.mutate(file);
            }}
          />
        </label>
      )}
      {upload.isPending && (
        <p role="status">
          {ui("Đang tải lên:")} {progress}%
        </p>
      )}
      {(uploadError || actionFailed || files.isError) && (
        <p role="alert">
          {uploadError
            ? problemMessage(uploadError)
            : actionFailed
              ? ui("Không thực hiện được. Hãy làm mới trạng thái tệp rồi thử lại.")
              : ui("Không tải được danh sách tệp.")}
        </p>
      )}
      {!compact && (
        <Button type="button" size="sm" prominence="internal" onClick={() => void files.refetch()}>
          {ui("Làm mới")}
        </Button>
      )}
      <p className="text-xs text-content-secondary">
        {ui("Tệp gần đây")}
        {!compact && ui(" · Đã chọn {{v1}}/20", { v1: selected.length })}
      </p>
      {files.isPending && (
        <p role="status" className="px-2 text-sm text-content-muted">
          {ui("Đang tải tệp…")}
        </p>
      )}
      {files.loaded && files.entries.length === 0 && (
        <p className="px-2 text-sm text-content-muted">{ui("Chưa có tệp nào.")}</p>
      )}
      <div className="flex max-h-64 flex-col gap-2 overflow-y-auto">
        {files.unavailable.map((id) => (
          <div key={id} className="text-sm">
            {ui("Tệp không còn khả dụng (")}
            {id})
            <Button
              type="button"
              size="sm"
              prominence="internal"
              onClick={() => select(selected.filter((value) => value !== id))}
            >
              {ui("Gỡ")}
            </Button>
          </div>
        ))}
        {shown.map((file) => (
          <RecentFileRow
            key={file.id}
            file={file}
            compact={compact}
            selected={selected.includes(file.id)}
            full={selected.length >= selectionLimit}
            onToggle={(pick) =>
              select(pick ? [...selected, file.id] : selected.filter((id) => id !== file.id))
            }
            onRetry={() => retry.mutate({ path: { fileId: file.id } })}
            onFinalize={() => finalize.mutate({ path: { fileId: file.id } })}
            onDelete={async () => {
              await remove.mutateAsync({ path: { fileId: file.id } });
            }}
          />
        ))}
      </div>
      {compact ? (
        <Button type="button" size="sm" prominence="internal" onClick={onMore}>
          {moreLabel ?? ui("Tất cả tệp gần đây")}
        </Button>
      ) : (
        <div className="flex gap-2">
          <Button
            type="button"
            size="sm"
            prominence="internal"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(0, offset - recentPage))}
          >
            {ui("Trước")}
          </Button>
          <Button
            type="button"
            size="sm"
            prominence="internal"
            disabled={files.pageSize < recentPage || offset >= 9990}
            onClick={() => setOffset(offset + recentPage)}
          >
            {ui("Tiếp")}
          </Button>
        </div>
      )}
    </fieldset>
  );
}

/** One recent file: chosen with its checkbox, and in the full list retried, confirmed or deleted in place. */
function RecentFileRow({
  file,
  compact,
  selected,
  full,
  onToggle,
  onRetry,
  onFinalize,
  onDelete,
}: {
  file: ChatFile;
  compact: boolean;
  selected: boolean;
  /** The message already carries as many files as it may. */
  full: boolean;
  onToggle: (pick: boolean) => void;
  onRetry: () => void;
  onFinalize: () => void;
  onDelete: () => Promise<void>;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <label className="flex min-w-0 flex-1 items-center gap-2">
        <Checkbox
          checked={selected}
          disabled={!selected && (file.status !== "READY" || full)}
          onCheckedChange={(checked) => onToggle(checked === true)}
        />
        <FileStatusIcon file={file} />
        <span className="truncate" title={file.filename}>
          {file.filename}
        </span>
      </label>
      {/* Failures stay readable where they can be acted on; touch screens have no tooltip. */}
      {!compact && file.status === "FAILED" && (
        <span className="text-xs text-status-danger-content">{fileStatusLabel(file, ui)}</span>
      )}
      {!compact && file.status === "FAILED" && file.errorCode !== "UPLOAD_EXPIRED" && (
        <Button type="button" size="sm" prominence="internal" onClick={onRetry}>
          {ui("Thử lại")}
        </Button>
      )}
      {!compact && file.status === "UPLOADING" && (
        <Button type="button" size="sm" prominence="internal" onClick={onFinalize}>
          {ui("Xác nhận tải lên")}
        </Button>
      )}
      {!compact && (
        <ConfirmDialog
          trigger={
            <IconButton
              type="button"
              size="sm"
              prominence="internal"
              disabled={selected}
              aria-label={ui("Xóa {{v1}}", { v1: file.filename })}
              title={ui("Xóa {{v1}}", { v1: file.filename })}
            >
              <Trash2 />
            </IconButton>
          }
          title={ui("Xóa tệp {{v1}}?", { v1: file.filename })}
          description={ui(
            "Nội dung tệp sẽ không còn đọc được, kể cả trong hội thoại cũ. Tên tệp trong lịch sử vẫn được giữ.",
          )}
          confirmLabel={ui("Xóa tệp")}
          pendingLabel={ui("Đang xóa…")}
          errorMessage={() =>
            "Không xóa được. Nếu tệp đang gắn với trợ lý/dự án, hãy gỡ và lưu trước khi xóa."
          }
          onConfirm={onDelete}
        />
      )}
    </div>
  );
}
