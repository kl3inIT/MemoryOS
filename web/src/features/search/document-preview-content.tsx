import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { getSearchDocumentOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { stripGeneratedTitlePrefix } from "./search-presentation";
import type { DocumentSelection } from "./document-preview-dialog";

export function DocumentPreviewContent({
  selection,
  variant = "search",
}: {
  selection: DocumentSelection;
  variant?: "search" | "chat";
}) {
  const [activeMatchIndex, setActiveMatchIndex] = useState(selection.activeMatchIndex);
  const activeMatch = selection.matches[activeMatchIndex] ?? selection.matches[0];
  const [from, setFrom] = useState(activeMatch?.from ?? 0);
  const matchingPassageRef = useRef<HTMLElement | null>(null);
  const detail = useQuery({
    ...getSearchDocumentOptions({
      path: { documentId: selection.documentId },
      query: { generation: selection.generation, from },
    }),
    retry: false,
    // Each opening reads the requested generation from the authorized reader.
    staleTime: 0,
    gcTime: 0,
  });

  useEffect(() => {
    if (detail.data && matchingPassageRef.current) {
      matchingPassageRef.current.scrollIntoView({ block: "center" });
    }
  }, [activeMatch?.matchingOrdinal, detail.data]);

  return (
    <>
      <div
        className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4 sm:px-6"
        aria-busy={detail.isPending}
      >
        {detail.isPending ? (
          <p role="status" className="py-12 text-center font-main-ui-body text-content-secondary">
            Loading document context…
          </p>
        ) : detail.isError || !detail.data ? (
          <div
            role="alert"
            className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content"
          >
            <p className="font-main-ui-body">
              This document is unavailable or has changed. Search again to find its current version.
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
                  aria-label={isMatch ? "Selected match" : undefined}
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
                    <p className="mb-2 font-secondary-action text-content-muted">Selected match</p>
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
          aria-label="Document matches"
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
              Match {index + 1}
            </Button>
          ))}
        </nav>
      ) : null}

      {variant === "chat" && activeMatch && from !== activeMatch.from && (
        <div className="shrink-0 border-t border-border-subtle px-5 py-2">
          <Button size="sm" prominence="internal" onClick={() => setFrom(activeMatch.from)}>
            Back to cited passage
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
            Earlier context
          </Button>
          <Button
            prominence="secondary"
            disabled={!detail.data.hasMore}
            onClick={() => setFrom(from + 20)}
          >
            More context
          </Button>
        </footer>
      ) : null}
    </>
  );
}
