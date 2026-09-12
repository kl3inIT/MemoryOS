import { useAppTranslation } from "@/i18n/use-app-translation";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { getSearchDocument, readChatFilePassages } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { stripGeneratedTitlePrefix } from "./search-presentation";
import type { DocumentSelection } from "./document-preview-dialog";

export function DocumentPreviewContent({
  selection,
  variant = "search",
  fileId,
}: {
  selection: DocumentSelection;
  variant?: "search" | "chat";
  fileId?: string;
}) {
  const ui = useAppTranslation();

  const { actorId, authorizationVersion } = useApplicationSession();
  const [activeMatchIndex, setActiveMatchIndex] = useState(selection.activeMatchIndex);
  const activeMatch = selection.matches[activeMatchIndex] ?? selection.matches[0];
  const [from, setFrom] = useState(activeMatch?.from ?? 0);
  const contentRef = useRef<HTMLDivElement>(null);
  const matchingPassageRef = useRef<HTMLElement | null>(null);
  const detail = useQuery({
    queryFn: async ({ signal }) =>
      (fileId
        ? await readChatFilePassages({
            path: { fileId },
            query: { generation: selection.generation, from },
            signal,
            throwOnError: true,
          })
        : await getSearchDocument({
            path: { documentId: selection.documentId },
            query: { generation: selection.generation, from },
            signal,
            throwOnError: true,
          })
      ).data,
    queryKey: [
      "document-preview",
      actorId,
      authorizationVersion,
      fileId ?? selection.documentId,
      selection.generation,
      from,
    ],
    retry: false,
    // Each opening reads the requested generation from the authorized reader.
    staleTime: 0,
    gcTime: 0,
  });

  useEffect(() => {
    if (detail.data && from === activeMatch?.from && matchingPassageRef.current) {
      matchingPassageRef.current.scrollIntoView({ block: "center" });
    } else if (detail.data && contentRef.current) {
      contentRef.current.scrollTop = 0;
    }
  }, [activeMatch?.matchingOrdinal, activeMatch?.from, from, detail.data]);

  return (
    <>
      <div
        ref={contentRef}
        className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4 sm:px-6"
        aria-busy={detail.isPending}
      >
        {detail.isPending ? (
          <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
            {ui("Đang tải nội dung tài liệu…")}
          </p>
        ) : detail.isError || !detail.data ? (
          <div
            role="alert"
            className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content"
          >
            <p className="font-main-ui-body">
              {ui("Tài liệu không còn khả dụng hoặc đã thay đổi. Hãy tìm lại phiên bản hiện tại.")}
            </p>
          </div>
        ) : (
          <div className={variant === "chat" ? "space-y-1" : "space-y-4"}>
            {detail.data.passages.map((passage) => {
              const isMatch =
                !!activeMatch &&
                passage.ordinal >= activeMatch.matchingOrdinal &&
                passage.ordinal <= (activeMatch.matchingEndOrdinal ?? activeMatch.matchingOrdinal);
              return (
                <article
                  key={passage.ordinal}
                  ref={
                    passage.ordinal === activeMatch?.matchingOrdinal
                      ? matchingPassageRef
                      : undefined
                  }
                  aria-current={isMatch ? "true" : undefined}
                  aria-label={isMatch ? ui("Đoạn được chọn") : undefined}
                  className={
                    isMatch
                      ? variant === "chat"
                        ? "scroll-m-8 border-l-2 border-border-strong bg-surface-sunken/60 px-4 py-3"
                        : "scroll-m-8 rounded-xl border border-border-default bg-status-info-surface p-4"
                      : variant === "chat"
                        ? "px-4 py-3"
                        : "border-t border-border-subtle pt-4 first:border-0 first:pt-0"
                  }
                >
                  {isMatch && variant !== "chat" ? (
                    <p className="mb-2 font-secondary-action text-content-muted">
                      {ui("Đoạn được chọn")}
                    </p>
                  ) : null}
                  <p
                    className={`whitespace-pre-wrap break-words text-content-primary ${variant === "chat" ? "text-sm leading-7" : "font-main-content-body"}`}
                  >
                    {stripGeneratedTitlePrefix(passage.content, detail.data.title).trim()}
                  </p>
                </article>
              );
            })}
          </div>
        )}
      </div>

      {selection.matches.length > 1 ? (
        <nav
          aria-label={ui("Các đoạn khớp")}
          className="flex shrink-0 gap-2 overflow-x-auto border-t border-border-subtle px-5 py-3 sm:px-6"
        >
          {selection.matches.map((match, index) => (
            <Button
              key={match.matchingOrdinal}
              size="sm"
              prominence={index === activeMatchIndex ? "primary" : "secondary"}
              aria-current={index === activeMatchIndex ? "true" : undefined}
              onClick={() => {
                setActiveMatchIndex(index);
                setFrom(match.from);
              }}
            >
              {ui("Đoạn")} {index + 1}
            </Button>
          ))}
        </nav>
      ) : null}

      {variant === "chat" && activeMatch && from !== activeMatch.from && (
        <div className="shrink-0 border-t border-border-subtle px-5 py-2">
          <Button size="sm" prominence="internal" onClick={() => setFrom(activeMatch.from)}>
            {ui("Về đoạn trích dẫn")}
          </Button>
        </div>
      )}

      {!detail.isPending && !detail.isError && detail.data ? (
        <footer className="flex shrink-0 flex-col gap-2 border-t border-border-subtle px-5 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] sm:flex-row sm:justify-between sm:px-6 sm:pb-4">
          <Button
            prominence="secondary"
            disabled={from === 0}
            onClick={() => setFrom(Math.max(0, from - 20))}
          >
            {ui("Phần trước")}
          </Button>
          <Button
            prominence="secondary"
            disabled={!detail.data.hasMore}
            onClick={() => setFrom(from + 20)}
          >
            {ui("Phần tiếp")}
          </Button>
        </footer>
      ) : null}
    </>
  );
}
