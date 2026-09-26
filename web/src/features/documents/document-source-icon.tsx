import {
  File,
  FileCode,
  FileImage,
  FileSpreadsheet,
  FileText,
  FileType,
  Presentation,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { findSourceProvider } from "@/features/sources/shared/source-provider-catalog";
import {
  documentKind,
  type DocumentKind,
  type DocumentSourceType,
} from "./document-source-presentation";

const ICONS: Record<DocumentKind, LucideIcon> = {
  pdf: FileText,
  document: FileText,
  spreadsheet: FileSpreadsheet,
  presentation: Presentation,
  text: FileType,
  code: FileCode,
  image: FileImage,
  generic: File,
};

// Kind tints use semantic status roles so both themes stay legible; generic documents stay neutral.
const TINTS: Record<DocumentKind, string> = {
  pdf: "text-status-danger-content",
  document: "text-status-info-content",
  spreadsheet: "text-status-success-content",
  presentation: "text-status-warning-content",
  text: "text-content-secondary",
  code: "text-content-secondary",
  image: "text-content-secondary",
  generic: "text-content-secondary",
};

/** The tinted file-type glyph alone, for rows and chips that draw their own frame. */
export function DocumentKindIcon({
  mediaType,
  filename,
  className,
}: {
  mediaType?: string | null;
  filename?: string | null;
  className?: string;
}) {
  const kind = documentKind(mediaType, filename);
  const Icon = ICONS[kind];
  return (
    <Icon
      aria-hidden="true"
      data-slot="document-kind-icon"
      data-kind={kind}
      className={cn("size-4 shrink-0", TINTS[kind], className)}
    />
  );
}

/**
 * File-type glyph with a provider badge. Uploaded files are the default provenance, so only external
 * providers receive a badge. Missing metadata (answers saved before it was recorded) renders the
 * neutral document glyph. Decorative: callers expose type and provider as text.
 */
export function DocumentSourceIcon({
  mediaType,
  filename,
  sourceTypes,
  size = "md",
  className,
}: {
  mediaType?: string | null;
  /** The document title, used for the type when the media type is generic (a file sent as octet-stream). */
  filename?: string | null;
  sourceTypes?: readonly DocumentSourceType[];
  size?: "xs" | "md" | "lg";
  className?: string;
}) {
  const kind = documentKind(mediaType, filename);
  const Icon = ICONS[kind];
  const badge = sourceTypes?.find((type) => type !== "FILE");
  // At chip size a badge is an unreadable smudge; the adjacent text names the provider instead.
  const Badge = badge && size !== "xs" ? findSourceProvider(badge)?.icon : undefined;
  return (
    <span
      aria-hidden="true"
      data-slot="document-source-icon"
      data-kind={kind}
      data-provider={badge?.toLowerCase()}
      className={cn(
        "relative inline-flex shrink-0 items-center justify-center",
        size === "xs"
          ? "size-3.5"
          : size === "md"
            ? "size-8 rounded-lg border border-border-subtle bg-surface-subtle"
            : "size-9 rounded-lg border border-border-default bg-surface-subtle shadow-xs",
        className,
      )}
    >
      <Icon className={cn(size === "xs" ? "size-3.5" : "size-4", TINTS[kind])} />
      {Badge ? (
        <span
          className={cn(
            "absolute flex items-center justify-center rounded-full border border-border-subtle bg-surface-base",
            size === "xs" ? "-right-1 -bottom-1 size-2.5" : "-right-1.5 -bottom-1.5 size-4",
          )}
        >
          <Badge
            data-slot="document-source-provider"
            className={size === "xs" ? "size-2" : "size-3"}
          />
        </span>
      ) : null}
    </span>
  );
}
