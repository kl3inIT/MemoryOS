import { useQuery } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Copy, Download, Undo2, X } from "lucide-react";
import { useEffect, useState, type ComponentType } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { IconButton } from "@/components/ui/icon-button";
import { Spinner } from "@/components/ui/spinner";
import { DownloadView } from "@/features/preview/download-view";
import { FilePreview } from "@/features/preview/file-preview";
import { ImageControls } from "@/features/preview/image-view";
import { useObjectUrl } from "@/features/preview/use-object-url";
import {
  codeLanguage,
  lineCount,
  parseCsv,
  previewKind,
  previewSize,
  type PreviewKind,
} from "@/features/preview/preview-kind";
import { uiLocale } from "@/i18n/format";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { fileSize } from "@/lib/file-size";
import { ChatFileAskComposer, type AskExtras } from "./file-ask-composer";
import { downloadUrl, type PreviewTarget } from "./file-preview";
import { filePreviewOptions, type Loaded } from "./file-preview-load";
import { ImageCropper } from "./image-cropper";
import { cropPixels } from "./image-crop";
import { useImageEdit, ZOOM, type ImageEdit } from "./use-image-edit";

/** Onyx PreviewModal: a centered modal sized by variant, with a header description and a floating footer. */
export function ChatFilePreviewModal({
  target: opened,
  siblings,
  onClose,
  onCloseAutoFocus,
  onSaveImage,
  ask,
}: {
  target: PreviewTarget;
  /** The files shown beside this one, in the order the page lists them; enables previous/next. */
  siblings?: readonly PreviewTarget[];
  onClose: () => void;
  onCloseAutoFocus?: (event: Event) => void;
  /** Keeps an edited image where the modal was opened from; without it an edit can only be downloaded. */
  onSaveImage?: (file: File) => void | Promise<void>;
  /**
   * Opens a conversation about this file with the model `ModelPicker` chose. `edited` is the image the viewer is
   * currently showing when a crop has been applied, so the question is asked about what is on screen. Surfaces
   * inside Chat leave it out: they are already a conversation.
   */
  ask?: {
    onAsk: (
      target: PreviewTarget,
      question: string,
      extras: AskExtras & { edited?: File },
    ) => Promise<void>;
    ModelPicker: ComponentType<{ disabled: boolean }>;
  };
}) {
  const ui = useAppTranslation();
  const [target, setTarget] = useState(opened);
  const image = useImageEdit({
    filename: target.filename,
    mediaType: target.mediaType,
    onSaveImage,
  });
  const gallery = siblings ?? [];
  const at = gallery.findIndex((file) => file.source === target.source && file.id === target.id);
  const step = (delta: number) => {
    const next = gallery[at + delta];
    if (at < 0 || !next) return;
    setTarget(next);
    image.reset();
  };
  // Arrow keys step through the gallery, as an image viewer does; the dialog keeps Escape for closing.
  useEffect(() => {
    if (at < 0) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || image.cropping) return;
      if (event.key === "ArrowLeft") step(-1);
      else if (event.key === "ArrowRight") step(1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });
  const loaded = useQuery(filePreviewOptions(target));
  const guessed = previewKind(target.filename, target.mediaType ?? "application/octet-stream");
  const kind: PreviewKind = loaded.data?.kind ?? guessed;
  const size = previewSize(kind);
  const [docxWords, setDocxWords] = useState<{ words: number; text: string }>();
  const { edited, cropping } = image;
  const original = loaded.data?.kind === "image" ? loaded.data.blob : undefined;
  // Once a crop is applied the viewer shows it, so everything downstream reads one image.
  const picture = edited?.blob ?? original;
  const override = edited && original ? ({ kind: "image", blob: edited.blob } as const) : undefined;
  const filename = edited?.filename ?? target.filename;
  const shownFile = override ?? loaded.data;
  const view = shownFile ? describe(shownFile, filename, docxWords, ui) : undefined;
  // The cropper draws the same bytes the preview holds, so it shares the preview's object URL.
  const pictureSource = useObjectUrl(picture);
  const editedSource = useObjectUrl(edited?.blob);

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        data-slot="file-preview-modal"
        showCloseButton={false}
        aria-describedby={undefined}
        onOpenAutoFocus={(event) => event.preventDefault()}
        onCloseAutoFocus={onCloseAutoFocus}
        layout="flush"
        phone="screen"
        size={size}
      >
        <FilePreviewHeader
          filename={filename}
          description={view?.description}
          copy={view?.copy}
          download={cropping ? undefined : (editedSource ?? downloadUrl(target))}
          position={at >= 0 && gallery.length > 1 ? { at, total: gallery.length } : undefined}
          onStep={step}
        />
        <div className="relative flex min-h-0 flex-1 flex-col bg-surface-subtle">
          {loaded.isPending ? (
            <div className="flex flex-1 items-center justify-center" role="status">
              <div className="flex flex-col items-center gap-3">
                <span className="text-content-muted">
                  <Spinner className="size-8" />
                </span>
                {guessed === "pptx" && target.source === "generated" ? (
                  <span className="text-sm text-content-secondary">
                    {ui("Đang tạo bản xem trước trình chiếu…")}
                  </span>
                ) : (
                  <span className="sr-only">{ui("Đang đọc tệp…")}</span>
                )}
              </div>
            </div>
          ) : loaded.isError ? (
            <DownloadView
              href={downloadUrl(target)}
              filename={target.filename}
              message={ui("Không xem trước được tệp. Bạn vẫn có thể tải tệp xuống.")}
            />
          ) : (
            <>
              <div
                className={cn(
                  "flex min-h-0 flex-1 flex-col overflow-auto",
                  kind === "image" || cropping ? "pt-14" : "pt-3",
                  // The question floats over the file, so the file keeps room beneath it.
                  ask && !cropping ? "pb-28" : "pb-4",
                )}
              >
                {cropping && pictureSource ? (
                  <ImageCropper
                    src={pictureSource}
                    alt={filename}
                    rect={image.crop}
                    onRect={image.setCrop}
                    onNatural={image.setNatural}
                  />
                ) : (
                  <FilePreview
                    content={override ?? loaded.data}
                    filename={target.filename}
                    download={downloadUrl(target)}
                    image={{ zoom: image.zoom, rotation: image.rotation, onZoom: image.zoomBy }}
                    onDocx={setDocxWords}
                  />
                )}
              </div>
              {(kind === "image" || cropping) && (
                <ImageToolbar
                  image={image}
                  isImage={kind === "image"}
                  canSave={onSaveImage !== undefined}
                  onApply={() => image.applyCrop(picture)}
                />
              )}
            </>
          )}
          {ask && !cropping && (
            <ChatFileAskComposer
              name={filename}
              ModelPicker={ask.ModelPicker}
              onAsk={(question, extras) =>
                ask.onAsk(target, question, {
                  ...extras,
                  edited: edited && new File([edited.blob], edited.filename, { type: edited.type }),
                })
              }
            />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

/**
 * The viewer's own chrome: the way out first, then where the file is and what it is called, then the things
 * done to it. Nothing sits on the file itself, so the picture is read on an empty surface.
 */
function FilePreviewHeader({
  filename,
  description,
  copy,
  download,
  position,
  onStep,
}: {
  filename: string;
  description?: string;
  /** The text a copy takes; absent, there is nothing to copy. */
  copy?: string;
  /** Where a download reads; absent while a crop is being drawn, which also hides the copy. */
  download?: string;
  /** Where the file sits among the ones beside it, when there are several. */
  position?: { at: number; total: number };
  onStep: (delta: number) => void;
}) {
  const ui = useAppTranslation();
  return (
    <header className="flex shrink-0 items-center gap-2 px-3 py-2">
      <DialogClose asChild>
        <IconButton prominence="internal" size="sm" aria-label={ui("Đóng xem trước")}>
          <X />
        </IconButton>
      </DialogClose>
      <nav
        aria-label={ui("Đường dẫn tệp")}
        className="flex min-w-0 flex-1 items-center gap-1.5 font-secondary-body"
      >
        <span className="shrink-0 text-content-muted">{ui("Thư viện")}</span>
        <span className="shrink-0 text-content-muted" aria-hidden="true">
          /
        </span>
        <DialogTitle asChild>
          <span className="min-w-0 truncate text-content-primary" title={filename}>
            {filename}
          </span>
        </DialogTitle>
        {description && (
          <>
            <span className="shrink-0 text-content-muted" aria-hidden="true">
              ·
            </span>
            <span className="shrink-0 truncate text-content-muted">{description}</span>
          </>
        )}
      </nav>
      {position && (
        <div className="flex shrink-0 items-center gap-1">
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Tệp trước")}
            disabled={position.at === 0}
            onClick={() => onStep(-1)}
          >
            <ChevronLeft />
          </IconButton>
          <span className="font-secondary-body tabular-nums text-content-muted">
            {ui("{{position}}/{{total}}", { position: position.at + 1, total: position.total })}
          </span>
          <IconButton
            prominence="internal"
            size="sm"
            aria-label={ui("Tệp sau")}
            disabled={position.at === position.total - 1}
            onClick={() => onStep(1)}
          >
            <ChevronRight />
          </IconButton>
        </div>
      )}
      {download !== undefined && (
        <div className="flex shrink-0 items-center gap-1">
          {copy !== undefined && <CopyButton text={copy} />}
          <IconButton prominence="internal" size="sm" asChild aria-label={ui("Tải xuống")}>
            {/* A download takes what the viewer shows, so an applied crop is what lands on disk. */}
            <a href={download} download={filename}>
              <Download />
            </a>
          </IconButton>
        </div>
      )}
    </header>
  );
}

/**
 * The picture's tools float over it rather than under it; what the file is is said once, in the header, so a
 * narrow window never reads it twice.
 */
function ImageToolbar({
  image,
  isImage,
  canSave,
  onApply,
}: {
  image: ImageEdit;
  isImage: boolean;
  canSave: boolean;
  onApply: () => void;
}) {
  const ui = useAppTranslation();
  const { crop, natural, cropping, edited } = image;
  return (
    <div className="pointer-events-none absolute inset-x-0 top-3 flex justify-center px-3">
      <div className="pointer-events-auto flex max-w-full flex-wrap items-center gap-1 rounded-full border border-border-subtle bg-surface-overlay px-1.5 py-1 shadow-lg">
        {isImage && (
          <ImageControls
            zoom={image.zoom}
            bare
            maxZoom={ZOOM.max}
            cropping={cropping}
            crop={crop && { selected: true, ...(natural && cropPixels(crop, natural)) }}
            onZoom={image.setZoom}
            onRotate={image.turn}
            onCrop={image.toggleCropping}
          />
        )}
        {image.cropFailed && (
          <span role="alert" className="px-2 font-secondary-body text-status-danger-content">
            {ui("Không cắt được ảnh.")}
          </span>
        )}
        {cropping ? (
          <Button size="sm" disabled={!crop || image.busy} onClick={onApply}>
            {image.applying ? ui("Đang cắt…") : ui("Cắt")}
          </Button>
        ) : (
          edited && (
            // The applied crop is what the viewer, a download, a save and a question now use.
            <>
              <span className="px-2 font-secondary-body tabular-nums text-content-muted">
                {ui("Đã cắt · {{width}} × {{height}} px", edited.pixels)}
              </span>
              {canSave && (
                <Button size="sm" disabled={image.busy} onClick={image.saveEdited}>
                  {image.saving ? ui("Đang lưu…") : ui("Lưu thành tệp mới")}
                </Button>
              )}
              <IconButton
                prominence="internal"
                size="sm"
                aria-label={ui("Về ảnh gốc")}
                onClick={image.revert}
              >
                <Undo2 />
              </IconButton>
            </>
          )
        )}
      </div>
    </div>
  );
}

type Translate = ReturnType<typeof useAppTranslation>;

/** Onyx headerDescription, footer text and copy text per variant. */
function describe(
  loaded: Loaded,
  filename: string,
  docx: { words: number; text: string } | undefined,
  ui: Translate,
): { description?: string; copy?: string } {
  const locale = uiLocale();
  switch (loaded.kind) {
    case "code":
    case "text": {
      const lines = lineCount(loaded.text);
      const size = fileSize(loaded.bytes, locale);
      return {
        description:
          loaded.kind === "code"
            ? ui("{{size}} · {{language}} · {{lines}} dòng", {
                size,
                language: codeLanguage(filename, "code"),
                lines,
              })
            : ui("{{size}} · {{lines}} dòng", { size, lines }),
        copy: loaded.text,
      };
    }
    case "markdown":
      return { copy: loaded.text };
    case "csv": {
      const [header = [], ...rows] = parseCsv(loaded.text);
      const columns = Math.max(header.length, ...rows.map((row) => row.length));
      return {
        description: ui("{{size}} · {{columns}} cột · {{rows}} dòng", {
          size: fileSize(loaded.bytes, locale),
          columns,
          rows: rows.length,
        }),
        copy: loaded.text,
      };
    }
    case "xlsx":
      return { description: ui("{{count}} trang tính", { count: loaded.sheets.length }) };
    case "pdf":
      return loaded.converted ? { description: ui("Bản xem trước PDF của trình chiếu") } : {};
    case "docx":
      return docx
        ? { description: ui("{{count}} từ", { count: docx.words }), copy: docx.text }
        : { description: ui("Tài liệu Word") };
    default:
      return {};
  }
}

function CopyButton({ text }: { text: string }) {
  const ui = useAppTranslation();
  const [copied, setCopied] = useState(false);
  return (
    <IconButton
      prominence="internal"
      size="sm"
      aria-label={copied ? ui("Đã sao chép") : ui("Sao chép")}
      onClick={() => {
        void navigator.clipboard?.writeText(text).then(() => {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1500);
        });
      }}
    >
      {copied ? <Check /> : <Copy />}
    </IconButton>
  );
}

export type { PreviewTarget };
