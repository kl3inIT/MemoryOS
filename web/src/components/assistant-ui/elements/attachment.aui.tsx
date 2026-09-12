// Adapted from assistant-ui (MIT), registry retrieved 2026-09-12.
"use client";

import { type PropsWithChildren, useState, type FC, isValidElement } from "react";
import { XIcon, FileText, Loader2Icon, AlertCircleIcon } from "lucide-react";
import { AttachmentPrimitive, ComposerPrimitive, useAuiState, useAui } from "@assistant-ui/react";
import { useTranslation } from "react-i18next";
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
        className="z-50 max-w-[min(20rem,calc(100vw-2rem))] rounded-lg border border-border-default bg-surface-overlay p-2 text-sm break-all shadow-md"
      />
    </TooltipPrimitive.Portal>
  );
}
function DialogContent({
  children,
  className,
  ...props
}: ComponentProps<typeof DialogPrimitive.Content>) {
  const { t } = useTranslation("attachments");
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
          <IconButton aria-label={t("closePreview")} className="absolute right-2 top-2">
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
  const { t } = useTranslation("attachments");
  const [isLoaded, setIsLoaded] = useState(false);
  return (
    <img
      src={src}
      alt={t("preview")}
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
  const { t } = useTranslation("attachments");
  const src = useAttachmentSrc();

  if (!src) return children;

  return (
    <Dialog>
      <DialogTrigger className="aui-attachment-preview-trigger cursor-zoom-in" asChild>
        {isValidElement(children) ? children : <button type="button">{children}</button>}
      </DialogTrigger>
      <DialogContent className="aui-attachment-preview-dialog-content [&>button]:bg-foreground/60 [&>button]:hover:bg-foreground/80 [&_svg]:text-background p-2 sm:max-w-3xl [&>button]:rounded-full [&>button]:p-1 [&>button]:opacity-100 [&>button]:ring-0!">
        <DialogTitle className="aui-sr-only sr-only">{t("previewTitle")}</DialogTitle>
        <div className="aui-attachment-preview bg-background relative mx-auto flex max-h-[80dvh] w-full items-center justify-center overflow-hidden rounded-sm">
          <AttachmentPreview src={src} />
        </div>
      </DialogContent>
    </Dialog>
  );
};

const AttachmentThumb: FC = () => {
  const { t } = useTranslation("attachments");
  const src = useAttachmentSrc();

  return (
    <Avatar className="aui-attachment-tile-avatar flex h-full w-full items-center justify-center rounded-none">
      <AvatarImage
        src={src}
        alt={t("preview")}
        className="aui-attachment-tile-image h-full w-full rounded-none object-cover"
      />
      <AvatarFallback className="flex h-full w-full items-center justify-center">
        <FileText className="aui-attachment-tile-fallback-icon text-muted-foreground/80 size-6 stroke-[1.5]" />
      </AvatarFallback>
    </Avatar>
  );
};

const AttachmentUI: FC = () => {
  const { t, i18n } = useTranslation("attachments");
  const status = useAuiState((s) => s.attachment.status);
  const name = useAuiState((s) => s.attachment.name);
  const size = useAuiState((s) => {
    if (s.attachment.file) return s.attachment.file.size;
    const part = s.attachment.content?.find((entry) => entry.type === "file");
    const value = part?.providerMetadata?.memoryos?.sizeBytes;
    return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
  });
  const sizeLabel =
    size == null
      ? undefined
      : new Intl.NumberFormat(i18n.resolvedLanguage, {
          style: "unit",
          unit: size >= 1024 * 1024 ? "megabyte" : size >= 1024 ? "kilobyte" : "byte",
          unitDisplay: "short",
          maximumFractionDigits: 1,
        }).format(size / (size >= 1024 * 1024 ? 1024 * 1024 : size >= 1024 ? 1024 : 1));
  const aui = useAui();
  const isComposer = aui.attachment.source !== "message";

  const isImage = useAuiState((s) => s.attachment.type === "image");

  const uploadState = useAuiState((s) =>
    s.attachment.status.type === "running"
      ? "uploading"
      : s.attachment.status.type === "incomplete" && s.attachment.status.reason === "error"
        ? "error"
        : undefined,
  );
  const isUploading = uploadState === "uploading";
  const isError = uploadState === "error";

  const statusMessage = isError
    ? t("failed")
    : isUploading
      ? status.type === "running" && status.progress === 1
        ? t("processing")
        : t("uploading")
      : undefined;

  return (
    <TooltipProvider>
      <Tooltip>
        <AttachmentPrimitive.Root
          className={cn(
            "aui-attachment-root relative flex w-64 max-w-full shrink-0 items-center gap-2 rounded-[14px] bg-muted p-2",
            isComposer && "animate-in fade-in-0 zoom-in-95 duration-200 motion-reduce:animate-none",
            isImage && !isComposer && "aui-attachment-root-message",
          )}
        >
          <AttachmentPreviewDialog>
            <TooltipTrigger asChild>
              <button
                type="button"
                className={cn(
                  "aui-attachment-tile relative flex min-w-0 flex-1 items-center gap-2.5 rounded-lg text-start outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  isError && "after:ring-destructive/60 dark:after:ring-destructive/60",
                )}
                aria-label={t("fileLabel", { name })}
              >
                <span className="relative size-9 shrink-0 overflow-hidden rounded-[10px] bg-background/50">
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
                </span>
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-sm font-medium">{name}</span>
                  {(statusMessage || sizeLabel) && (
                    <span
                      role={statusMessage ? "status" : undefined}
                      className={cn(
                        "truncate text-xs text-muted-foreground",
                        isError && "text-destructive",
                      )}
                    >
                      {statusMessage ?? sizeLabel}
                    </span>
                  )}
                </span>
              </button>
            </TooltipTrigger>
          </AttachmentPreviewDialog>
          {isComposer && <AttachmentRemove />}
        </AttachmentPrimitive.Root>
        <TooltipContent side="top">
          <AttachmentPrimitive.Name />
          {statusMessage && <p>{statusMessage}</p>}
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
};

const AttachmentRemove: FC = () => {
  const { t } = useTranslation("attachments");
  return (
    <AttachmentPrimitive.Remove asChild>
      <IconButton
        aria-label={t("remove")}
        title={t("remove")}
        size="sm"
        prominence="secondary"
        className="z-10 size-6 min-h-0 min-w-0 shrink-0 rounded-full border-0 p-0"
      >
        <XIcon className="aui-attachment-remove-icon size-3 stroke-[2.5]" />
      </IconButton>
    </AttachmentPrimitive.Remove>
  );
};

export const ComposerAttachments: FC = () => (
  <div className="aui-composer-attachments flex min-w-0 w-full flex-row flex-wrap items-center gap-2 p-1 empty:hidden">
    <ComposerPrimitive.Attachments>{() => <AttachmentUI />}</ComposerPrimitive.Attachments>
  </div>
);
