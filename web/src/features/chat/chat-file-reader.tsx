import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useFileSrc } from "@/hooks/use-attachment-src";
import { downloadChatFile, getChatFile, readChatFileText } from "@/lib/hey-api/sdk.gen";
import { chatFileSchema } from "./chat-files";

const textWindow = z.object({
  text: z.string(),
  offset: z.number().int(),
  nextOffset: z.number().int(),
  totalCharacters: z.number().int(),
});

/** Mounted only while the owner opens a file. No private content persists in the query cache. */
export function ChatFileReader({ fileId }: { fileId: string }) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const [offset, setOffset] = useState(0);
  const [imageFailed, setImageFailed] = useState(false);
  const metadata = useQuery({
    queryKey: ["chat-file-reader", actorId, authorizationVersion, fileId],
    gcTime: 0,
    staleTime: 0,
    retry: false,
    queryFn: async ({ signal }) =>
      chatFileSchema.parse(
        (await getChatFile({ path: { fileId }, signal, throwOnError: true })).data,
      ),
  });
  const file = metadata.data;
  const ready = !metadata.isError && file?.status === "READY";
  const image = ready && ["image/png", "image/jpeg", "image/webp"].includes(file.mediaType);
  const content = useQuery({
    queryKey: ["chat-file-reader-content", actorId, authorizationVersion, fileId, offset, image],
    enabled: ready,
    gcTime: 0,
    staleTime: 0,
    retry: false,
    queryFn: async ({ signal }) => {
      if (image) {
        if (file.sizeBytes > 20 * 1024 * 1024) throw new Error("Image exceeds preview limit");
        const { data } = await downloadChatFile({
          path: { fileId },
          parseAs: "blob",
          signal,
          throwOnError: true,
        });
        if (!(data instanceof Blob) || data.size !== file.sizeBytes)
          throw new Error("Invalid file content");
        return { image: new File([data], file.filename, { type: file.mediaType }) };
      }
      return {
        text: textWindow.parse(
          (
            await readChatFileText({
              path: { fileId },
              query: { offset, count: 16000 },
              signal,
              throwOnError: true,
            })
          ).data,
        ),
      };
    },
  });
  const src = useFileSrc(ready ? content.data?.image : undefined);
  const window = ready ? content.data?.text : undefined;
  const failed = metadata.isError || (file && !ready) || content.isError || imageFailed;
  return (
    <div className="space-y-3">
      {(metadata.isPending || (ready && content.isPending)) && <p role="status">Đang đọc tệp…</p>}
      {failed && (
        <p role="alert">
          Không đọc được tệp. Tệp có thể đã bị xóa, chưa xử lý xong hoặc bạn không còn quyền truy
          cập.
        </p>
      )}
      {!failed && src && (
        <img
          src={src}
          alt={file?.filename ?? "Ảnh đính kèm"}
          className="max-h-[60dvh] max-w-full rounded-lg object-contain"
          onError={() => setImageFailed(true)}
        />
      )}
      {!failed && window && (
        <>
          <pre className="max-h-[55dvh] overflow-y-auto rounded-lg bg-surface-sunken p-3 text-sm whitespace-pre-wrap break-words">
            {window.text || "Tệp không có nội dung văn bản."}
          </pre>
          <p className="text-sm text-content-secondary">
            Ký tự {window.offset}–{window.nextOffset} / {window.totalCharacters}
          </p>
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              prominence="secondary"
              disabled={offset === 0}
              onClick={() => setOffset(Math.max(0, offset - 16000))}
            >
              Phần trước
            </Button>
            <Button
              type="button"
              size="sm"
              prominence="secondary"
              disabled={window.nextOffset >= window.totalCharacters}
              onClick={() => setOffset(window.nextOffset)}
            >
              Phần tiếp
            </Button>
          </div>
        </>
      )}
      {ready && (
        <Button asChild size="sm" prominence="secondary">
          <a
            href={`/api/chat/files/${encodeURIComponent(fileId)}/content`}
            download={file.filename}
          >
            Tải bản gốc
          </a>
        </Button>
      )}
    </div>
  );
}
