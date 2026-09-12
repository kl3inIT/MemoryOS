import { useEffect, useRef, useState } from "react";
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
import { chatFileSchema, uploadChatFile, type ChatFile } from "./chat-files";

export function ChatFilePicker({
  selected,
  onSelect,
  disabled = false,
}: {
  selected: string[];
  onSelect: (ids: string[], files: ChatFile[]) => void;
  disabled?: boolean;
}) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const [offset, setOffset] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string>();
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
    <fieldset
      disabled={disabled || uploading || acting}
      className="space-y-2 rounded-xl border border-border-default p-3"
    >
      <legend>Tệp gần đây ({selected.length}/20)</legend>
      <label className="block text-sm">
        Tải tệp lên
        <input
          type="file"
          aria-label="Tải tệp lên"
          className="block max-w-full"
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
                if (!upload.signal.aborted)
                  setError(cause instanceof Error ? cause.message : "Không tải được tệp.");
              })
              .finally(() => {
                if (!upload.signal.aborted) setUploading(false);
              });
          }}
        />
      </label>
      {uploading && <p role="status">Đang tải lên: {progress}%</p>}
      {(error || files.isError) && <p role="alert">{error ?? "Không tải được danh sách tệp."}</p>}
      <Button type="button" size="sm" prominence="internal" onClick={() => void files.refetch()}>
        Làm mới
      </Button>
      <div className="max-h-64 space-y-2 overflow-y-auto">
        {files.data?.unavailable.map((id) => (
          <div key={id} className="text-sm">
            Tệp không còn khả dụng ({id})
            <Button
              type="button"
              size="sm"
              prominence="internal"
              onClick={() => select(selected.filter((value) => value !== id))}
            >
              Gỡ
            </Button>
          </div>
        ))}
        {files.data?.entries.map((file) => (
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
              <span className="break-all">{file.filename}</span>
            </label>
            <span>
              {file.status === "READY"
                ? file.searchReady === false
                  ? "Đọc được · Chưa sẵn sàng tìm kiếm"
                  : "Sẵn sàng"
                : file.status === "PROCESSING"
                  ? "Đang xử lý…"
                  : file.status === "FAILED"
                    ? file.errorCode === "UPLOAD_EXPIRED"
                      ? "Upload hết hạn · Chọn file để tải lại"
                      : "Xử lý lỗi"
                    : file.status === "UPLOADING"
                      ? "Chưa xác nhận upload"
                      : "Đã xóa"}
            </span>
            {file.status === "FAILED" && file.errorCode !== "UPLOAD_EXPIRED" && (
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
                Thử lại
              </Button>
            )}
            {file.status === "UPLOADING" && (
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
                Xác nhận tải lên
              </Button>
            )}
            <ConfirmDialog
              trigger={
                <Button
                  type="button"
                  size="sm"
                  prominence="internal"
                  disabled={selected.includes(file.id)}
                >
                  Xóa
                </Button>
              }
              title={`Xóa tệp ${file.filename}?`}
              description="Nội dung tệp sẽ không còn đọc được, kể cả trong hội thoại cũ. Tên tệp trong lịch sử vẫn được giữ."
              confirmLabel="Xóa tệp"
              pendingLabel="Đang xóa…"
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
          </div>
        ))}
      </div>
      <div className="flex gap-2">
        <Button
          type="button"
          size="sm"
          prominence="internal"
          disabled={offset === 0}
          onClick={() => setOffset(Math.max(0, offset - 30))}
        >
          Trước
        </Button>
        <Button
          type="button"
          size="sm"
          prominence="internal"
          disabled={(files.data?.pageSize ?? 0) < 30 || offset >= 9990}
          onClick={() => setOffset(offset + 30)}
        >
          Tiếp
        </Button>
      </div>
    </fieldset>
  );
}
