import type { AttachmentAdapter, PendingAttachment } from "@assistant-ui/react";
import { z } from "zod";
import { sameOriginMutationHeaders } from "@/lib/api";
import {
  getChatFile,
  getChatFilePolicy,
  initiateChatFileUpload,
  finalizeChatFileUpload,
} from "@/lib/hey-api/sdk.gen";
import { putAuthorizedObject, sha256 } from "@/features/sources/direct-upload";

export const chatFileSchema = z.object({
  id: z.string().uuid(),
  filename: z.string(),
  mediaType: z.string(),
  sizeBytes: z.number(),
  status: z.enum(["UPLOADING", "PROCESSING", "READY", "FAILED", "DELETING", "DELETED"]),
  errorCode: z.string().nullish(),
  searchReady: z.boolean().optional(),
});
export type ChatFile = z.infer<typeof chatFileSchema>;
export const fileReference = (id: string) => `memoryos-file:${id}`;
export function fileIdFromReference(value: string): string | undefined {
  const id = value.startsWith("memoryos-file:") ? value.slice(14) : "";
  return z.string().uuid().safeParse(id).success ? id : undefined;
}

// Only a fetch network failure can mean the idempotent write committed but its response was lost.
// Do not retry HTTP authorization/validation errors or canceled operations.
async function retryLostResponse<T>(send: () => Promise<T>, signal: AbortSignal): Promise<T> {
  try {
    return await send();
  } catch (error) {
    if (!(error instanceof TypeError) || signal.aborted) throw error;
    return send();
  }
}

export async function uploadChatFile(
  file: File,
  requestId: string,
  signal: AbortSignal,
  progress: (value: number) => void,
) {
  const { data: policy } = await getChatFilePolicy({ signal, throwOnError: true });
  if (!policy.maxSizeBytes || file.size < 1 || file.size > policy.maxSizeBytes)
    throw new Error(
      `Tệp phải nhỏ hơn hoặc bằng ${Math.floor((policy.maxSizeBytes ?? 0) / 1048576)} MiB.`,
    );
  const checksum = await sha256(file, signal);
  const { data } = await retryLostResponse(
    () =>
      initiateChatFileUpload({
        body: {
          requestId,
          filename: file.name,
          mediaType: file.type || "application/octet-stream",
          sizeBytes: file.size,
          sha256: checksum,
        },
        headers: sameOriginMutationHeaders,
        signal,
        throwOnError: true,
      }),
    signal,
  );
  let saved = chatFileSchema.parse(data.file);
  if (saved.status === "UPLOADING") {
    const authorization = z
      .object({
        method: z.literal("PUT"),
        uri: z.string().url(),
        requiredHeaders: z.record(z.string(), z.string()),
        expiresAt: z.string(),
      })
      .parse(data.upload);
    await putAuthorizedObject(
      { ...authorization, uploadUrl: authorization.uri },
      file,
      signal,
      progress,
    );
    saved = chatFileSchema.parse(
      (
        await retryLostResponse(
          () =>
            finalizeChatFileUpload({
              path: { fileId: saved.id },
              headers: sameOriginMutationHeaders,
              signal,
              throwOnError: true,
            }),
          signal,
        )
      ).data,
    );
  }
  return saved;
}

export async function waitForChatFile(id: string, signal: AbortSignal): Promise<ChatFile> {
  for (;;) {
    signal.throwIfAborted();
    const file = chatFileSchema.parse(
      (await getChatFile({ path: { fileId: id }, signal, throwOnError: true })).data,
    );
    if (file.status === "READY") return file;
    if (file.status !== "PROCESSING")
      throw new Error(
        `Tệp chưa đọc được: ${file.errorCode ?? file.status}. Mở Tệp gần đây để kiểm tra hoặc thử lại.`,
      );
    await new Promise<void>((resolve, reject) => {
      const abort = () => {
        clearTimeout(timer);
        reject(signal.reason);
      };
      const timer = setTimeout(() => {
        signal.removeEventListener("abort", abort);
        resolve();
      }, 1500);
      signal.addEventListener("abort", abort, { once: true });
    });
  }
}

export function createChatAttachmentAdapter(
  onError: (message: string) => void,
): AttachmentAdapter & { cancelPending: () => void } {
  const pending = new Map<string, AbortController>();
  return {
    accept: "*",
    cancelPending() {
      pending.forEach((controller) => controller.abort());
      pending.clear();
    },
    async *add({ file }) {
      const id = crypto.randomUUID();
      const controller = new AbortController();
      pending.set(id, controller);
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(30 * 60_000)]);
      const type = ["image/png", "image/jpeg", "image/webp"].includes(file.type)
        ? ("image" as const)
        : ("file" as const);
      const base = { id, type, name: file.name, contentType: file.type, file, content: [] };
      yield {
        ...base,
        status: { type: "running", reason: "uploading", progress: 0 },
      } satisfies PendingAttachment;
      try {
        let progress = 0;
        let wake = Promise.withResolvers<void>();
        let done = false;
        const upload = uploadChatFile(file, id, signal, (value) => {
          progress = value / 100;
          wake.resolve();
        });
        void upload.then(
          () => {
            done = true;
            wake.resolve();
          },
          () => {
            done = true;
            wake.resolve();
          },
        );
        while (!done) {
          await wake.promise;
          wake = Promise.withResolvers<void>();
          yield {
            ...base,
            status: { type: "running", reason: "uploading", progress },
          } satisfies PendingAttachment;
        }
        const saved = await upload;
        yield {
          ...base,
          status: { type: "running", reason: "uploading", progress: 1 },
        } satisfies PendingAttachment;
        const ready = await waitForChatFile(saved.id, signal);
        yield {
          ...base,
          content: [
            {
              type: "file",
              filename: ready.filename,
              mimeType: ready.mediaType,
              data: fileReference(ready.id),
            },
          ],
          status: { type: "requires-action", reason: "composer-send" },
        } satisfies PendingAttachment;
      } catch (error) {
        if (!controller.signal.aborted)
          onError(error instanceof Error ? error.message : "Không tải được tệp.");
        throw error;
      } finally {
        pending.delete(id);
      }
    },
    async send(attachment) {
      if (
        attachment.status.type !== "requires-action" ||
        !attachment.content?.some(
          (part) =>
            part.type === "file" && typeof part.data === "string" && fileIdFromReference(part.data),
        )
      )
        throw new Error("Tệp chưa sẵn sàng. Hãy chờ xử lý xong hoặc gỡ tệp lỗi trước khi gửi.");
      return { ...attachment, status: { type: "complete" }, content: attachment.content ?? [] };
    },
    async remove(attachment) {
      pending.get(attachment.id)?.abort();
    },
  };
}
