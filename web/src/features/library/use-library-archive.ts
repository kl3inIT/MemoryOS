import { useEffect, useRef } from "react";
import { useMutation } from "@tanstack/react-query";
import { getChatLibraryArchive, requestChatLibraryArchive } from "@/lib/hey-api/sdk.gen";
import type { ChatLibraryArchive } from "@/lib/hey-api/types.gen";
import { actionErrorText } from "@/lib/action-errors";
import type { LibraryFile } from "./library";

type ArchiveState =
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

/** How often the browser asks whether the Worker has packed the ZIP. */
const pollInterval = 1500;
/** How long a selection may take to pack before the wait is abandoned. */
const packTimeout = 10 * 60_000;

/** The Worker could not pack the ZIP; its reason is an operator diagnostic, not the person's to read. */
class ArchiveFailed extends Error {}

function wait(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const abort = () => {
      clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, pollInterval);
    signal.addEventListener("abort", abort, { once: true });
  });
}

/**
 * Downloading a selection as one ZIP (MEM-152): the request is recorded, a Worker packs it, and the browser
 * polls until it is ready and then downloads it. A file that disappeared meanwhile is reported, not hidden. The
 * whole wait is one mutation, so its state is the notice the page shows.
 */
export function useLibraryArchive(): LibraryArchive {
  const running = useRef<AbortController>(null);
  useEffect(() => () => running.current?.abort(), []);
  const pack = useMutation({
    mutationFn: async (files: readonly LibraryFile[]) => {
      running.current?.abort();
      const controller = new AbortController();
      running.current = controller;
      const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(packTimeout)]);
      let { data: archive } = await requestChatLibraryArchive({
        body: { files: files.map((file) => ({ source: file.source, id: file.id })) },
        signal,
      });
      while (archive.status === "PENDING" || archive.status === "RUNNING") {
        await wait(signal);
        archive = (await getChatLibraryArchive({ path: { archiveId: archive.id }, signal })).data;
      }
      if (archive.status !== "READY") throw new ArchiveFailed(archive.status);
      // The download is a navigation to the owner-private route, so no bytes pass through this code.
      const link = document.createElement("a");
      link.href = archiveContentUrl(archive.id);
      link.rel = "noopener";
      document.body.append(link);
      link.click();
      link.remove();
      return archive;
    },
  });

  const state: ArchiveState = pack.isPending
    ? { phase: "packing", fileCount: pack.variables.length }
    : pack.isSuccess
      ? { phase: "ready", archive: pack.data }
      : pack.isError
        ? {
            phase: "failed",
            // The page says what the person can do about a failed archive; a failed request names itself.
            message: pack.error instanceof ArchiveFailed ? "" : actionErrorText(pack.error),
          }
        : { phase: "idle" };
  return {
    state,
    start: async (files) => {
      // A newer request replaces this one; its own outcome is the one shown.
      await pack.mutateAsync(files).catch(() => undefined);
    },
    reset: pack.reset,
  };
}
