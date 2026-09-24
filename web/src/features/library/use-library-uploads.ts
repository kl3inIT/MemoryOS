import { useCallback, useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { ErrorMessage } from "@/lib/problem-presentation";
import { chatAttachmentProblem, uploadChatFile } from "./files";
import { chatLibraryKey } from "./library";

export type LibraryUpload = {
  id: string;
  name: string;
  sizeBytes: number;
  progress: number;
  state: "running" | "done" | "failed" | "cancelled";
  error?: ErrorMessage;
};

/**
 * Uploading straight into the library (MEM-152): the same `/api/chat/files` pipeline the composer uses, so a
 * file taken in here is an ordinary upload that belongs to no conversation. Each file has its own row and its
 * own cancellation, following the Proton Drive and ElevenLabs upload trays.
 */
export function useLibraryUploads() {
  const cache = useQueryClient();
  const [uploads, setUploads] = useState<LibraryUpload[]>([]);
  const controllers = useRef(new Map<string, AbortController>());
  useEffect(() => {
    const running = controllers.current;
    return () => running.forEach((controller) => controller.abort());
  }, []);

  const change = useCallback((id: string, next: Partial<LibraryUpload>) => {
    setUploads((current) =>
      current.map((upload) => (upload.id === id ? { ...upload, ...next } : upload)),
    );
  }, []);

  const start = useCallback(
    (files: readonly File[]) => {
      for (const file of files) {
        const id = crypto.randomUUID();
        const controller = new AbortController();
        controllers.current.set(id, controller);
        setUploads((current) => [
          ...current,
          { id, name: file.name, sizeBytes: file.size, progress: 0, state: "running" },
        ]);
        void uploadChatFile(
          file,
          id,
          AbortSignal.any([controller.signal, AbortSignal.timeout(30 * 60_000)]),
          (value) => change(id, { progress: value }),
        )
          .then(async () => {
            change(id, { state: "done", progress: 100 });
            await cache.invalidateQueries({ queryKey: chatLibraryKey });
          })
          .catch((cause: unknown) => {
            if (controller.signal.aborted) change(id, { state: "cancelled" });
            else change(id, { state: "failed", error: chatAttachmentProblem(cause) });
          })
          .finally(() => controllers.current.delete(id));
      }
    },
    [cache, change],
  );

  const cancel = useCallback((id: string) => {
    controllers.current.get(id)?.abort();
    controllers.current.delete(id);
  }, []);

  const dismiss = useCallback(
    () => setUploads((current) => current.filter((upload) => upload.state === "running")),
    [],
  );

  return { uploads, start, cancel, dismiss };
}
