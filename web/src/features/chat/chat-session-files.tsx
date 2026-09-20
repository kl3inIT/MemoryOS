import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Paperclip, Trash2 } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { IconButton } from "@/components/ui/icon-button";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { chatActionError } from "./chat-action-utils";
import { fileSize } from "./chat-code";
import { ChatFilePreviewModal } from "./chat-file-preview-modal";
import { downloadUrl, type PreviewTarget } from "./chat-file-preview";
import {
  chatLibraryKey,
  deleteLibraryFile,
  libraryPreviewTarget,
  loadLibrary,
  refusedBy,
  usageLabel,
  type LibraryFile,
} from "./chat-library";

/**
 * "Files in this conversation" (MEM-144): the library list narrowed to one conversation, so a person can find
 * what they attached or what a run produced without scrolling the transcript.
 */
export function ChatSessionFiles({ sessionId }: { sessionId: string }) {
  const ui = useAppTranslation();
  const cache = useQueryClient();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<PreviewTarget>();
  const [confirming, setConfirming] = useState<LibraryFile>();
  const [refusal, setRefusal] = useState<string>();

  const filter = { query: "", sources: [], categories: [], sort: "NEWEST" as const, sessionId };
  const files = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "session", sessionId],
    queryFn: ({ signal }) => loadLibrary(filter, 0, signal),
    enabled: open,
  });
  const items = files.data?.items ?? [];

  const remove = async (file: LibraryFile) => {
    try {
      await deleteLibraryFile(file, AbortSignal.timeout(30000));
      setRefusal(undefined);
    } catch (failure) {
      const holders = refusedBy(failure);
      setRefusal(
        holders.length > 0
          ? ui("Đang dùng trong {{name}}", { name: holders.join(", ") })
          : chatActionError(failure),
      );
    } finally {
      await cache.invalidateQueries({ queryKey: chatLibraryKey });
    }
  };

  return (
    <>
      <IconButton
        size="sm"
        prominence="internal"
        aria-label={ui("Tệp trong hội thoại")}
        onClick={() => setOpen(true)}
      >
        <Paperclip />
      </IconButton>
      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="gap-0">
          <SheetHeader className="border-b border-border-subtle pr-12">
            <SheetTitle>{ui("Tệp trong hội thoại")}</SheetTitle>
            <SheetDescription>
              {files.data
                ? ui("{{count}} tệp · {{size}}", {
                    count: files.data.totalCount,
                    size: fileSize(files.data.totalBytes, i18n.language),
                  })
                : ui("Đang tải thư viện…")}
            </SheetDescription>
          </SheetHeader>
          <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto p-4">
            {files.isError && <p role="alert">{ui("Không tải được thư viện.")}</p>}
            {files.isSuccess && items.length === 0 && (
              <p role="status" className="text-content-muted">
                {ui("Hội thoại này chưa có tệp nào.")}
              </p>
            )}
            {refusal && (
              <p role="alert" className="text-sm text-content-danger">
                {refusal}
              </p>
            )}
            {items.map((file) => (
              <div
                key={file.id}
                className="flex items-center gap-2 rounded-lg border border-border-default px-3 py-2"
              >
                <button
                  type="button"
                  className="min-w-0 flex-1 text-left hover:underline"
                  onClick={() => setPreview(libraryPreviewTarget(file))}
                >
                  <span className="block truncate">{file.filename}</span>
                  <span className="block text-xs text-content-muted">
                    {fileSize(file.sizeBytes, i18n.language)}
                  </span>
                  {usageLabel(file) && (
                    <span className="block text-xs text-content-muted">
                      {ui("Đang dùng trong {{name}}", { name: usageLabel(file) })}
                    </span>
                  )}
                </button>
                <IconButton size="sm" prominence="internal" asChild aria-label={ui("Tải về")}>
                  <a href={downloadUrl(libraryPreviewTarget(file))} download={file.filename}>
                    <Download />
                  </a>
                </IconButton>
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Xoá {{name}}", { name: file.filename })}
                  disabled={!file.deletable}
                  onClick={() => setConfirming(file)}
                >
                  <Trash2 />
                </IconButton>
              </div>
            ))}
          </div>
        </SheetContent>
      </Sheet>
      {preview && <ChatFilePreviewModal target={preview} onClose={() => setPreview(undefined)} />}
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
          if (file) await remove(file);
        }}
      />
    </>
  );
}
