import type { ReactNode } from "react";
import { DocumentKindIcon } from "@/features/search/document-source-icon";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { fileSize } from "@/features/chat/interpreter/chat-code";
import { highlightParts, type ContentMatch } from "./library";

/**
 * Content search results (MEM-152): the file, then the passages that matched, so the person recognises the file
 * by what they remembered about it rather than by its name.
 */
export function LibraryContentMatches({
  matches,
  query,
  onOpen,
  actions,
}: {
  matches: readonly ContentMatch[];
  query: string;
  onOpen: (match: ContentMatch) => void;
  actions?: (match: ContentMatch) => ReactNode;
}) {
  const ui = useAppTranslation();
  return (
    <ul className="flex flex-col gap-2">
      {matches.map((match) => (
        <li key={match.file.id} className="rounded-lg border border-border-subtle px-3 py-2.5">
          <div className="flex items-start gap-2">
            <button
              type="button"
              className="flex min-w-0 flex-1 items-start gap-2 text-left"
              onClick={() => onOpen(match)}
            >
              <DocumentKindIcon
                mediaType={match.file.mediaType}
                filename={match.file.filename}
                className="mt-0.5 size-4 shrink-0"
              />
              <span className="min-w-0 flex-1">
                <span className="block truncate font-medium">{match.file.filename}</span>
                <span className="block text-xs text-content-muted">
                  {fileSize(match.file.sizeBytes, i18n.language)}
                </span>
              </span>
            </button>
            {actions?.(match)}
          </div>
          <ul className="mt-2 flex flex-col gap-1">
            {match.passages.map((passage) => (
              <li
                key={passage.ordinal}
                className="line-clamp-3 border-l-2 border-border-subtle pl-2 text-sm text-content-secondary"
              >
                {highlightParts(passage.text, query).map((part, index) =>
                  part.match ? (
                    <mark key={index} className="rounded-sm bg-highlight-match/50 text-inherit">
                      {part.text}
                    </mark>
                  ) : (
                    <span key={index}>{part.text}</span>
                  ),
                )}
              </li>
            ))}
          </ul>
          {match.passages.length === 0 && (
            <p className="mt-1 text-sm text-content-muted">{ui("Khớp nội dung tệp.")}</p>
          )}
        </li>
      ))}
    </ul>
  );
}
