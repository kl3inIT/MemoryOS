import { useRef, useState, type ReactNode } from "react";
import { CircleAlert, CircleCheck, Upload, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Progress } from "@/components/ui/progress";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { cn } from "@/lib/utils";
import { useProblemMessage } from "@/lib/use-problem-message";
import { fileSize } from "@/lib/file-size";
import type { LibraryUpload } from "./use-library-uploads";

/** A whole-page drop target; the browser's own file dialog stays on the Upload button. */
export function LibraryDropZone({
  onFiles,
  children,
}: {
  onFiles: (files: File[]) => void;
  children: ReactNode;
}) {
  const ui = useAppTranslation();
  const [over, setOver] = useState(false);
  const depth = useRef(0);
  return (
    <div
      onDragEnter={(event) => {
        if (!event.dataTransfer.types.includes("Files")) return;
        depth.current += 1;
        setOver(true);
      }}
      onDragOver={(event) => {
        if (event.dataTransfer.types.includes("Files")) event.preventDefault();
      }}
      onDragLeave={() => {
        depth.current = Math.max(0, depth.current - 1);
        if (depth.current === 0) setOver(false);
      }}
      onDrop={(event) => {
        if (!event.dataTransfer.types.includes("Files")) return;
        event.preventDefault();
        depth.current = 0;
        setOver(false);
        const files = [...event.dataTransfer.files];
        if (files.length > 0) onFiles(files);
      }}
      className={cn(
        "relative flex min-h-0 flex-1 flex-col",
        over && "rounded-xl outline-2 outline-offset-4 outline-dashed outline-border-strong",
      )}
    >
      {children}
      {over && (
        <p
          role="status"
          className="pointer-events-none absolute inset-x-0 top-2 mx-auto w-fit rounded-full bg-primary px-4 py-1 text-sm text-primary-foreground"
        >
          {ui("Thả tệp vào đây để tải lên")}
        </p>
      )}
    </div>
  );
}

/** The Upload button, keyboard-reachable like any button, with a hidden multi-file input behind it. */
export function LibraryUploadButton({ onFiles }: { onFiles: (files: File[]) => void }) {
  const ui = useAppTranslation();
  const input = useRef<HTMLInputElement>(null);
  return (
    <>
      <Button size="sm" prominence="secondary" onClick={() => input.current?.click()}>
        <Upload className="size-4" aria-hidden="true" />
        {ui("Tải lên")}
      </Button>
      <input
        ref={input}
        type="file"
        multiple
        aria-label={ui("Tải tệp lên thư viện")}
        className="sr-only"
        onChange={(event) => {
          const files = [...(event.target.files ?? [])];
          event.target.value = "";
          if (files.length > 0) onFiles(files);
        }}
      />
    </>
  );
}

export function LibraryUploadTray({
  uploads,
  onCancel,
  onDismiss,
}: {
  uploads: readonly LibraryUpload[];
  onCancel: (id: string) => void;
  onDismiss: () => void;
}) {
  const ui = useAppTranslation();
  const problemMessage = useProblemMessage();
  if (uploads.length === 0) return null;
  const running = uploads.filter((upload) => upload.state === "running").length;
  return (
    <section
      aria-label={ui("Tiến trình tải lên")}
      className="fixed right-4 bottom-4 z-30 w-80 max-w-[calc(100vw-2rem)] rounded-xl border border-border-default bg-surface-overlay shadow-md"
    >
      <header className="flex items-center justify-between border-b border-border-subtle px-3 py-2">
        <span className="text-sm font-medium" role="status">
          {running > 0
            ? ui("Đang tải lên {{count}} tệp", { count: running })
            : ui("Đã tải lên xong")}
        </span>
        <IconButton
          size="sm"
          prominence="internal"
          aria-label={ui("Đóng danh sách tải lên")}
          onClick={onDismiss}
        >
          <X />
        </IconButton>
      </header>
      <ul className="max-h-64 overflow-y-auto p-2">
        {uploads.map((upload) => (
          <li key={upload.id} className="flex items-center gap-2 px-1 py-1.5">
            <span className="min-w-0 flex-1">
              <span className="block truncate text-sm" title={upload.name}>
                {upload.name}
              </span>
              {upload.state === "running" ? (
                <Progress value={upload.progress} className="mt-1" />
              ) : (
                <span
                  className={cn(
                    "block text-xs",
                    upload.state === "failed" ? "text-status-danger-content" : "text-content-muted",
                  )}
                >
                  {upload.state === "done"
                    ? ui("Đã tải lên · {{size}}", {
                        size: fileSize(upload.sizeBytes, i18n.language),
                      })
                    : upload.state === "cancelled"
                      ? ui("Đã huỷ")
                      : upload.error
                        ? problemMessage(upload.error)
                        : ui("Tải lên lỗi")}
                </span>
              )}
            </span>
            {upload.state === "running" && (
              <>
                <span className="text-xs text-content-muted">{upload.progress}%</span>
                <IconButton
                  size="sm"
                  prominence="internal"
                  aria-label={ui("Huỷ tải {{name}}", { name: upload.name })}
                  onClick={() => onCancel(upload.id)}
                >
                  <X />
                </IconButton>
              </>
            )}
            {upload.state === "done" && (
              <CircleCheck className="size-4 text-status-success-content" aria-hidden="true" />
            )}
            {upload.state === "failed" && (
              <CircleAlert className="size-4 text-status-danger-content" aria-hidden="true" />
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
