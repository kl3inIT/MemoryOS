import { useAppTranslation } from "@/i18n/use-app-translation";
import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { ErrorState } from "@/components/assistant-ui/elements/error-state";
import { useQuery } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { useFileSrc } from "@/hooks/use-attachment-src";
import {
  downloadChatFileOptions,
  getChatFileOptions,
  readChatFileTextOptions,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import type { ChatFileResponse, ChatFileTextResponse } from "@/lib/hey-api/types.gen";

/** Characters one reader window asks for. */
const WINDOW = 16000;
/** Images larger than this are offered for download only. */
const IMAGE_PREVIEW_LIMIT = 20 * 1024 * 1024;
const previewableImages = ["image/png", "image/jpeg", "image/webp"];

/** A stored file's metadata as the reader uses it; the published contract marks its fields optional. */
function readerFileOf({
  id,
  filename = "",
  mediaType = "",
  sizeBytes = 0,
  status,
}: ChatFileResponse) {
  if (id === undefined) throw new TypeError("A file carries its id.");
  return { id, filename, mediaType, sizeBytes, status };
}

/** One window of a file's extracted text, with the server's Unicode cursor. */
function textWindowOf({
  text = "",
  offset = 0,
  nextOffset = 0,
  totalCharacters = 0,
}: ChatFileTextResponse) {
  return { text, offset, nextOffset, totalCharacters };
}

// Private content is read fresh each time and leaves the cache with the reader.
const privateRead = { gcTime: 0, staleTime: 0, retry: false } as const;

/** Mounted only while the owner opens a file. No private content persists in the query cache. */
export function ChatFileReader({
  fileId,
  initialOffset = 0,
  citationCount,
}: {
  fileId: string;
  initialOffset?: number;
  citationCount?: number;
}) {
  const ui = useAppTranslation();
  const { t } = useTranslation("chatStatus");
  const { t: reader, i18n } = useTranslation("reader");
  const number = new Intl.NumberFormat(i18n.resolvedLanguage);
  const [offset, setOffset] = useState(initialOffset);
  const [imageFailed, setImageFailed] = useState(false);
  const metadata = useQuery({
    ...getChatFileOptions({ path: { fileId } }),
    ...privateRead,
    select: readerFileOf,
  });
  const file = metadata.data;
  const ready = !metadata.isError && file?.status === "READY";
  const image = ready && previewableImages.includes(file.mediaType);
  const tooLarge = image && file.sizeBytes > IMAGE_PREVIEW_LIMIT;
  // Stable per file, so the image and its object URL are built once per download.
  const asImage = useCallback(
    (data: unknown) => {
      if (!file || !(data instanceof Blob) || data.size !== file.sizeBytes)
        throw new Error("Invalid file content");
      return new File([data], file.filename, { type: file.mediaType });
    },
    [file],
  );
  const picture = useQuery({
    ...downloadChatFileOptions({ path: { fileId }, parseAs: "blob" }),
    ...privateRead,
    enabled: image && !tooLarge,
    select: asImage,
  });
  const text = useQuery({
    ...readChatFileTextOptions({ path: { fileId }, query: { offset, count: WINDOW } }),
    ...privateRead,
    enabled: ready && !image,
    select: textWindowOf,
  });
  const content = image ? picture : text;
  const src = useFileSrc(image ? picture.data : undefined);
  const window = ready && !image ? text.data : undefined;
  const failed = metadata.isError || (file && !ready) || tooLarge || content.isError || imageFailed;
  return (
    <div className="flex flex-col gap-3">
      {(metadata.isPending || (ready && !tooLarge && content.isPending)) && (
        <p role="status">{reader("reading")}</p>
      )}
      {failed && <ErrorState title={t("fileUnavailable")} detail={t("fileUnavailableDetail")} />}
      {!failed && src && (
        <img
          src={src}
          alt={file?.filename ?? reader("image")}
          className="max-h-128 max-w-full rounded-lg object-contain"
          onError={() => setImageFailed(true)}
        />
      )}
      {!failed && window && (
        <>
          <pre className="max-h-120 overflow-y-auto rounded-lg bg-surface-sunken p-3 text-sm whitespace-pre-wrap break-words">
            {citationCount && offset === initialOffset ? (
              <>
                <mark className="bg-status-info-surface text-content-primary">
                  {Array.from(window.text).slice(0, citationCount).join("")}
                </mark>
                {Array.from(window.text).slice(citationCount).join("")}
              </>
            ) : (
              window.text || ui("Tệp không có nội dung văn bản.")
            )}
          </pre>
          <p className="text-sm text-content-secondary">
            {reader("characters", {
              from: number.format(window.offset),
              to: number.format(window.nextOffset),
              total: number.format(window.totalCharacters),
            })}
          </p>
          <div className="flex flex-wrap gap-2">
            {citationCount && offset !== initialOffset && (
              <Button
                type="button"
                size="sm"
                prominence="internal"
                onClick={() => setOffset(initialOffset)}
              >
                {reader("returnCitation")}
              </Button>
            )}
            <Button
              type="button"
              size="sm"
              prominence="secondary"
              disabled={offset === 0}
              onClick={() => setOffset(Math.max(0, offset - WINDOW))}
            >
              {reader("previous")}
            </Button>
            <Button
              type="button"
              size="sm"
              prominence="secondary"
              disabled={window.nextOffset >= window.totalCharacters}
              onClick={() => setOffset(window.nextOffset)}
            >
              {reader("next")}
            </Button>
          </div>
        </>
      )}
      {ready && (
        <Button asChild size="sm" prominence="secondary">
          <a
            href={`/api/chat/files/${encodeURIComponent(fileId)}/content`}
            download={file.filename}
          >
            {reader("download")}
          </a>
        </Button>
      )}
    </div>
  );
}
