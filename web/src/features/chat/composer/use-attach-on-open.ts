import { useEffect, useRef } from "react";
import { useNavigate, useSearch } from "@tanstack/react-router";
import { useAui } from "@assistant-ui/react";
import { waitForChatFile } from "@/features/library/files";
import { composerAttachment } from "./use-composer-file-selection";

/**
 * Opens a new conversation with a library file already in the composer, for pages that hand work over to Chat —
 * a meeting's minutes, say. The file may still be extracting when the page opens, so the wait happens here and the
 * person reads the empty thread meanwhile. The parameter is dropped once it is used, so a reload does not attach
 * the file twice.
 */
export function useAttachOnOpen() {
  const aui = useAui();
  const navigate = useNavigate();
  const search = useSearch({ strict: false }) as { attach?: string };
  const attached = useRef<string>(undefined);

  useEffect(() => {
    const fileId = search.attach;
    if (!fileId || attached.current === fileId) return;
    attached.current = fileId;
    const controller = new AbortController();
    void (async () => {
      try {
        const file = await waitForChatFile(fileId, controller.signal);
        if (!controller.signal.aborted)
          await aui.thread.composer().addAttachment(composerAttachment(file));
      } catch {
        // The file failed to extract or the person navigated away; the composer simply stays empty.
      } finally {
        if (!controller.signal.aborted) void navigate({ to: "/", search: {}, replace: true });
      }
    })();
    return () => controller.abort();
  }, [search.attach, aui, navigate]);
}
