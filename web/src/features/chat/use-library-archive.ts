import { useCallback, useEffect, useRef, useState } from "react";
import { getChatLibraryArchive, requestChatLibraryArchive } from "@/lib/hey-api/sdk.gen";
import type { ChatLibraryArchive } from "@/lib/hey-api/types.gen";
import { chatActionError } from "./chat-action-utils";
import type { LibraryFile } from "./chat-library";

export type ArchiveState =
  | { phase: "idle" }
  | { phase: "packing"; fileCount: number }
  | { phase: "ready"; archive: ChatLibraryArchive }
  | { phase: "failed"; message: string };

/** What the hook hands its page: where the ZIP is, and the two commands that move it. */
export type LibraryArchive = {
  state: ArchiveState;
  start: (files: readonly LibraryFile[]) => Promise<void>;
  reset: () => void;
};

/** The archive route is owner-private, so the browser downloads it as a normal navigation. */
export const archiveContentUrl = (id: string) => `/api/chat/library/archives/${id}/content`;

/**
 * Downloading a selection as one ZIP (MEM-152): the request is recorded, a Worker packs it, and the browser
 * polls until it is ready and then downloads it. A file that disappeared meanwhile is reported, not hidden.
 */
export function useLibraryArchive(): LibraryArchive {
  const [state, setState] = useState<ArchiveState>({ phase: "idle" });
  const running = useRef<AbortController>(null);
  useEffect(() => () => running.current?.abort(), []);

  const reset = useCallback(() => setState({ phase: "idle" }), []);

  const start = useCallback(async (files: readonly LibraryFile[]) => {
    running.current?.abort();
    const controller = new AbortController();
    running.current = controller;
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10 * 60_000)]);
    setState({ phase: "packing", fileCount: files.length });
    try {
      const { data } = await requestChatLibraryArchive({
        body: { files: files.map((file) => ({ source: file.source, id: file.id })) },
        signal,
      });
      let archive = data;
      while (archive.status === "PENDING" || archive.status === "RUNNING") {
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
        archive = (
          await getChatLibraryArchive({
            path: { archiveId: archive.id },
            signal,
          })
        ).data;
      }
      if (archive.status !== "READY") {
        // The server's reason is an operator diagnostic; the page says what the person can do.
        setState({ phase: "failed", message: "" });
        return;
      }
      setState({ phase: "ready", archive });
      // The download is a navigation to the owner-private route, so no bytes pass through this code.
      const link = document.createElement("a");
      link.href = archiveContentUrl(archive.id);
      link.rel = "noopener";
      document.body.append(link);
      link.click();
      link.remove();
    } catch (failure) {
      if (!controller.signal.aborted)
        setState({ phase: "failed", message: chatActionError(failure) });
    }
  }, []);

  return { state, start, reset };
}
