// Adapted from assistant-ui (MIT), registry retrieved 2026-09-12.
"use client";

import { type PropsWithChildren, useState, type FC, isValidElement } from "react";
import { XIcon, FileText, Loader2Icon, AlertCircleIcon } from "lucide-react";
import { AttachmentPrimitive, ComposerPrimitive, useAuiState, useAui } from "@assistant-ui/react";
import {
  Tooltip as TooltipPrimitive,
  Dialog as DialogPrimitive,
  Avatar as AvatarPrimitive,
} from "radix-ui";
import type { ComponentProps } from "react";
const Tooltip = TooltipPrimitive.Root;
const TooltipProvider = TooltipPrimitive.Provider;
const TooltipTrigger = TooltipPrimitive.Trigger;
const Dialog = DialogPrimitive.Root;
const DialogTitle = DialogPrimitive.Title;
const DialogTrigger = DialogPrimitive.Trigger;
const Avatar = AvatarPrimitive.Root;
const AvatarImage = AvatarPrimitive.Image;
const AvatarFallback = AvatarPrimitive.Fallback;
function TooltipContent(props: ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        {...props}
        className="z-50 max-w-xs rounded-lg border border-border-default bg-surface-overlay p-2 text-sm shadow-md"
      />
    </TooltipPrimitive.Portal>
  );
}
function DialogContent({
  children,
  className,
  ...props
}: ComponentProps<typeof DialogPrimitive.Content>) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-content-primary/20 backdrop-blur-[2px]" />
      <DialogPrimitive.Content
        {...props}
        aria-describedby={undefined}
        className={cn(
          "fixed left-1/2 top-1/2 z-50 w-[min(48rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-xl bg-surface-overlay outline-none",
          className,
        )}
      >
        {children}
        <DialogPrimitive.Close asChild>
          <IconButton aria-label="Đóng xem trước" className="absolute right-2 top-2">
            <XIcon />
          </IconButton>
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
import { IconButton } from "@/components/ui/icon-button";
import { useAttachmentSrc } from "@/hooks/use-attachment-src";
import { cn } from "@/lib/utils";

type AttachmentPreviewProps = {
  src: string;
};

const AttachmentPreview: FC<AttachmentPreviewProps> = ({ src }) => {
  const [isLoaded, setIsLoaded] = useState(false);
  return (
    <img
      src={src}
      alt="Xem trước ảnh"
      className={cn(
        "block h-auto max-h-[80vh] w-auto max-w-full rounded-sm object-contain transition-opacity duration-300 motion-reduce:transition-none",
        isLoaded
          ? "aui-attachment-preview-image-loaded opacity-100"
          : "aui-attachment-preview-image-loading opacity-0",
      )}
      onLoad={() => setIsLoaded(true)}
    />
  );
};

const AttachmentPreviewDialog: FC<PropsWithChildren> = ({ children }) => {
  const src = useAttachmentSrc();

  if (!src) return children;

  return (
    <Dialog>
      <DialogTrigger className="aui-attachment-preview-trigger cursor-zoom-in" asChild>
        {isValidElement(children) ? children : <button type="button">{children}</button>}
      </DialogTrigger>
      <DialogContent className="aui-attachment-preview-dialog-content [&>button]:bg-foreground/60 [&>button]:hover:bg-foreground/80 [&_svg]:text-background p-2 sm:max-w-3xl [&>button]:rounded-full [&>button]:p-1 [&>button]:opacity-100 [&>button]:ring-0!">
        <DialogTitle className="aui-sr-only sr-only">Xem trước ảnh đính kèm</DialogTitle>
        <div className="aui-attachment-preview bg-background relative mx-auto flex max-h-[80dvh] w-full items-center justify-center overflow-hidden rounded-sm">
          <AttachmentPreview src={src} />
        </div>
      </DialogContent>
    </Dialog>
  );
};

const AttachmentThumb: FC = () => {
  const src = useAttachmentSrc();

  return (
    <Avatar className="aui-attachment-tile-avatar h-full w-full rounded-none">
      <AvatarImage
        src={src}
        alt="Xem trước ảnh"
        className="aui-attachment-tile-image rounded-none object-cover"
      />
      <AvatarFallback>
        <FileText className="aui-attachment-tile-fallback-icon text-muted-foreground/80 size-6 stroke-[1.5]" />
      </AvatarFallback>
    </Avatar>
  );
};

const AttachmentUI: FC = () => {
  const status = useAuiState((s) => s.attachment.status);
  const aui = useAui();
  const isComposer = aui.attachment.source !== "message";

  const isImage = useAuiState((s) => s.attachment.type === "image");
  const typeLabel = useAuiState((s) => {
    const type = s.attachment.type;
    switch (type) {
      case "image":
        return "Ảnh";
      case "document":
        return "Tài liệu";
      case "file":
        return "Tệp";
      default:
        return type;
    }
  });

  const uploadState = useAuiState((s) =>
    s.attachment.status.type === "running"
      ? "uploading"
      : s.attachment.status.type === "incomplete" && s.attachment.status.reason === "error"
        ? "error"
        : undefined,
  );
  const isUploading = uploadState === "uploading";
  const isError = uploadState === "error";

  const errorMessage = useAuiState((s) =>
    s.attachment.status.type === "incomplete" && s.attachment.status.reason === "error"
      ? (s.attachment.status.message ?? "Tải tệp thất bại")
      : undefined,
  );

  return (
    <TooltipProvider>
      <Tooltip>
        <AttachmentPrimitive.Root
          className={cn(
            "aui-attachment-root relative",
            isComposer && "animate-in fade-in-0 zoom-in-95 duration-200 motion-reduce:animate-none",
            isImage && !isComposer && "aui-attachment-root-message only:*:first:size-24",
          )}
        >
          <AttachmentPreviewDialog>
            <TooltipTrigger asChild>
              <div
                className={cn(
                  "aui-attachment-tile bg-muted hover:after:bg-foreground/10 focus-visible:ring-ring/50 relative size-14 cursor-pointer overflow-hidden rounded-[calc(var(--composer-radius,1.5rem)-var(--composer-padding,8px))] transition-transform outline-none after:pointer-events-none after:absolute after:inset-0 after:rounded-[inherit] after:ring-1 after:ring-black/10 after:transition-colors after:ring-inset focus-visible:ring-1 active:scale-[0.96] motion-reduce:transition-none dark:after:ring-white/10",
                  isError && "after:ring-destructive/60 dark:after:ring-destructive/60",
                )}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    e.currentTarget.click();
                  } else if (e.key === " ") {
                    e.preventDefault();
                  }
                }}
                onKeyUp={(e) => {
                  if (e.key === " ") e.currentTarget.click();
                }}
                aria-label={`${typeLabel} đính kèm${
                  isError ? ", tải thất bại" : isUploading ? ", đang tải" : ""
                }`}
              >
                <AttachmentThumb />
                {isUploading && (
                  <div
                    aria-hidden="true"
                    className="aui-attachment-tile-uploading bg-background/60 animate-in fade-in-0 absolute inset-0 flex items-center justify-center backdrop-blur-[2px] motion-reduce:animate-none"
                  >
                    <Loader2Icon className="text-muted-foreground size-4 animate-spin" />
                  </div>
                )}
                {isError && (
                  <div
                    aria-hidden="true"
                    className="aui-attachment-tile-error bg-background/70 animate-in fade-in-0 absolute inset-0 flex items-center justify-center backdrop-blur-[2px] motion-reduce:animate-none"
                  >
                    <AlertCircleIcon className="text-destructive size-4" />
                  </div>
                )}
              </div>
            </TooltipTrigger>
          </AttachmentPreviewDialog>
          {isComposer && <AttachmentRemove />}
          <div className="max-w-48 truncate text-xs">
            <AttachmentPrimitive.Name />
          </div>
          {isComposer && (
            <span role="status" className="text-xs text-content-secondary">
              {status.type === "running"
                ? status.progress === 1
                  ? "Đang xử lý…"
                  : `Đang tải ${Math.round((status.progress ?? 0) * 100)}%`
                : status.type === "incomplete"
                  ? "Lỗi — thử lại trong Tệp gần đây"
                  : "Sẵn sàng"}
            </span>
          )}
        </AttachmentPrimitive.Root>
        <TooltipContent side="top">
          <AttachmentPrimitive.Name />
          {errorMessage && <p className="aui-attachment-error-message">{errorMessage}</p>}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
};

const AttachmentRemove: FC = () => {
  return (
    <AttachmentPrimitive.Remove asChild>
      <IconButton
        aria-label="Gỡ tệp"
        title="Gỡ tệp"
        size="sm"
        prominence="secondary"
        className="absolute right-0 top-0"
      >
        <XIcon className="aui-attachment-remove-icon size-3 stroke-[2.5]" />
      </IconButton>
    </AttachmentPrimitive.Remove>
  );
};

export const ComposerAttachments: FC = () => (
  <div className="aui-composer-attachments flex w-full flex-row flex-wrap items-center gap-2 empty:hidden">
    <ComposerPrimitive.Attachments>{() => <AttachmentUI />}</ComposerPrimitive.Attachments>
  </div>
);
