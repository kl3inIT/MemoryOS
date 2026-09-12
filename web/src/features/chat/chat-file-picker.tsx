import { useAppTranslation } from "@/i18n/use-app-translation";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Paperclip, Upload, FileText } from "lucide-react";
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover";
import { ChatDialog } from "./chat-dialog";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useApplicationSession } from "@/features/identity/application-session-context";
import {
  listChatFiles,
  getChatFile,
  retryChatFile,
  deleteChatFile,
  finalizeChatFileUpload,
} from "@/lib/hey-api/sdk.gen";
import { sameOriginMutationHeaders } from "@/lib/api";
import { chatFileSchema, uploadChatFile, chatAttachmentProblem, type ChatFile } from "./chat-files";
import { useProblemMessage } from "@/lib/use-problem-message";
import type { ErrorMessage } from "@/lib/problem-presentation";

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
            <Button
              type="button"
              size="sm"
              prominence="internal"
              disabled={props.disabled}
              aria-label={ui("Đính kèm tệp")}
              title={ui("Đính kèm tệp")}
            >
              <Paperclip className="size-4" />
            </Button>
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
      <ChatDialog
        title={ui("Tệp gần đây")}
        description={ui("Chọn lại tệp của bạn để sử dụng. Chỉ tệp đã xử lý xong mới được chọn.")}
        open={all}
        onOpenChange={setAll}
      >
        {all && <ChatFilePickerContent {...props} uploadAction={uploadAction} />}
      </ChatDialog>
    </>
  );
}

function ChatFilePickerContent({
  selected,
  onSelect,
  disabled = false,
  compact = false,
  onMore,
  uploadAction,
}: {
  selected: string[];
  onSelect: (ids: string[], files: ChatFile[]) => void;
  disabled?: boolean;
  compact?: boolean;
  onMore?: () => void;
  uploadAction?: ReactNode;
}) {
  const ui = useAppTranslation();

  const { actorId, authorizationVersion } = useApplicationSession();
  const [offset, setOffset] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | ErrorMessage>();
  const problemMessage = useProblemMessage();
  const [acting, setActing] = useState(false);
  const actingRef = useRef(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const files = useQuery({
    queryKey: ["chat-files", actorId, authorizationVersion, offset, selected],
    queryFn: async ({ signal }) => {
      const recent = chatFileSchema
        .array()
        .parse(
          (await listChatFiles({ query: { offset, limit: 30 }, signal, throwOnError: true })).data,
        );
      const pageSize = recent.length;
      const unavailable: string[] = [];
      for (const id of selected.filter((id) => !recent.some((file) => file.id === id))) {
        const result = await getChatFile({ path: { fileId: id }, signal });
        if (result.response?.status === 404) unavailable.push(id);
        else if (result.error) throw result.error;
        else recent.unshift(chatFileSchema.parse(result.data));
      }
      return { entries: recent, pageSize, unavailable };
    },
    refetchInterval: (query) =>
      query.state.data?.entries.some((file) => file.status === "PROCESSING") ? 1500 : false,
  });
  async function action(run: () => Promise<unknown>) {
    if (actingRef.current) return;
    actingRef.current = true;
    setActing(true);
    setError(undefined);
    try {
      await run();
      await files.refetch();
    } catch {
      setError("Không thực hiện được. Hãy làm mới trạng thái tệp rồi thử lại.");
    } finally {
      actingRef.current = false;
      setActing(false);
    }
  }
  function select(ids: string[]) {
    onSelect(
      ids,
      ids.flatMap((id) => files.data?.entries.find((entry) => entry.id === id) ?? []),
    );
  }
  return (
    <fieldset disabled={disabled || uploading || acting} className="min-w-0 space-y-3">
      {uploadAction ?? (
        <label className="flex cursor-pointer items-center gap-2 rounded-lg px-2 py-2 text-sm hover:bg-surface-sunken focus-within:ring-2">
          <Upload className="size-4" /> {ui("Tải tệp lên")}
          <input
            type="file"
            aria-label={ui("Tải tệp lên")}
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = "";
              if (!file) return;
              controller.current?.abort();
              const upload = new AbortController();
              controller.current = upload;
              setUploading(true);
              setError(undefined);
              setProgress(0);
              void uploadChatFile(
                file,
                crypto.randomUUID(),
                AbortSignal.any([upload.signal, AbortSignal.timeout(30 * 60_000)]),
                setProgress,
              )
                .then(() => files.refetch())
                .catch((cause: unknown) => {
                  if (!upload.signal.aborted) setError(chatAttachmentProblem(cause));
                })
                .finally(() => {
                  if (!upload.signal.aborted) setUploading(false);
                });
            }}
          />
        </label>
      )}
      {uploading && (
        <p role="status">
          {ui("Đang tải lên:")} {progress}%
        </p>
      )}
      {(error || files.isError) && (
        <p role="alert">
          {typeof error === "object"
            ? problemMessage(error)
            : ui(error ?? "Không tải được danh sách tệp.")}
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
      {files.data?.entries.length === 0 && (
        <p className="px-2 text-sm text-content-muted">{ui("Chưa có tệp nào.")}</p>
      )}
      <div className="max-h-64 space-y-2 overflow-y-auto">
        {files.data?.unavailable.map((id) => (
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
        {(compact ? files.data?.entries.slice(0, 3) : files.data?.entries)?.map((file) => (
          <div key={file.id} className="flex flex-wrap items-center gap-2 text-sm">
            <label className="flex min-w-0 flex-1 items-center gap-2">
              <input
                type="checkbox"
                checked={selected.includes(file.id)}
                disabled={
                  !selected.includes(file.id) && (file.status !== "READY" || selected.length >= 20)
                }
                onChange={(event) => {
                  const ids = event.target.checked
                    ? [...selected, file.id]
                    : selected.filter((id) => id !== file.id);
                  select(ids);
                }}
              />
              <FileText className="size-4 shrink-0 text-content-muted" />
              <span className="truncate" title={file.filename}>
                {file.filename}
              </span>
            </label>
            <span>
              {file.status === "READY"
                ? file.searchReady === false
                  ? ui("Đọc được · Chưa sẵn sàng tìm kiếm")
                  : ui("Sẵn sàng")
                : file.status === "PROCESSING"
                  ? ui("Đang xử lý…")
                  : file.status === "FAILED"
                    ? file.errorCode === "UPLOAD_EXPIRED"
                      ? ui("Upload hết hạn · Chọn file để tải lại")
                      : ui("Xử lý lỗi")
                    : file.status === "UPLOADING"
                      ? ui("Chưa xác nhận upload")
                      : ui("Đã xóa")}
            </span>
            {!compact && file.status === "FAILED" && file.errorCode !== "UPLOAD_EXPIRED" && (
              <Button
                type="button"
                size="sm"
                prominence="internal"
                onClick={() =>
                  void action(() =>
                    retryChatFile({
                      path: { fileId: file.id },
                      headers: sameOriginMutationHeaders,
                      throwOnError: true,
                    }),
                  )
                }
              >
                {ui("Thử lại")}
              </Button>
            )}
            {!compact && file.status === "UPLOADING" && (
              <Button
                type="button"
                size="sm"
                prominence="internal"
                onClick={() =>
                  void action(() =>
                    finalizeChatFileUpload({
                      path: { fileId: file.id },
                      headers: sameOriginMutationHeaders,
                      throwOnError: true,
                    }),
                  )
                }
              >
                {ui("Xác nhận tải lên")}
              </Button>
            )}
            {!compact && (
              <ConfirmDialog
                trigger={
                  <Button
                    type="button"
                    size="sm"
                    prominence="internal"
                    disabled={selected.includes(file.id)}
                  >
                    {ui("Xóa")}
                  </Button>
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
                onConfirm={async () => {
                  await deleteChatFile({
                    path: { fileId: file.id },
                    headers: sameOriginMutationHeaders,
                    throwOnError: true,
                  });
                  await files.refetch();
                }}
              />
            )}
          </div>
        ))}
      </div>
      {compact ? (
        <Button type="button" size="sm" prominence="internal" onClick={onMore}>
          {ui("Tất cả tệp gần đây")}
        </Button>
      ) : (
        <div className="flex gap-2">
          <Button
            type="button"
            size="sm"
            prominence="internal"
            disabled={offset === 0}
            onClick={() => setOffset(Math.max(0, offset - 30))}
          >
            {ui("Trước")}
          </Button>
          <Button
            type="button"
            size="sm"
            prominence="internal"
            disabled={(files.data?.pageSize ?? 0) < 30 || offset >= 9990}
            onClick={() => setOffset(offset + 30)}
          >
            {ui("Tiếp")}
          </Button>
        </div>
      )}
    </fieldset>
  );
}
