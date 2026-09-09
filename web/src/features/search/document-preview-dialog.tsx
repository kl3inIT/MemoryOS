import { useQuery } from "@tanstack/react-query";
import { X } from "lucide-react";
import { useEffect, useRef, useState, type RefObject } from "react";
import { Dialog } from "radix-ui";
import { Button } from "@/components/ui/button";
import { getSearchDocumentOptions } from "@/lib/hey-api/@tanstack/react-query.gen";
import { stripGeneratedTitlePrefix } from "./search-presentation";

export type DocumentSelection = {
  documentId: string;
  generation: string;
  title: string;
  matches: Array<{ from: number; matchingOrdinal: number }>;
  activeMatchIndex: number;
};

type DocumentPreviewDialogProps = {
  selection: DocumentSelection;
  returnFocusRef: RefObject<HTMLElement | null>;
  fallbackFocusRef: RefObject<HTMLElement | null>;
  onClose: () => void;
};

export function DocumentPreviewDialog({
  selection,
  returnFocusRef,
  fallbackFocusRef,
  onClose,
}: DocumentPreviewDialogProps) {
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
  });

  useEffect(() => {
    if (detail.data && matchingPassageRef.current) {
      matchingPassageRef.current.scrollIntoView({ block: "center" });
    }
  }, [activeMatch?.matchingOrdinal, detail.data]);

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/25 backdrop-blur-[2px] data-[state=closed]:animate-out data-[state=open]:animate-in data-[state=closed]:fade-out data-[state=open]:fade-in motion-reduce:animate-none" />
        <Dialog.Content
          className="fixed inset-x-0 bottom-0 z-50 flex max-h-[calc(100dvh-0.5rem)] min-h-[72dvh] flex-col overflow-hidden rounded-t-2xl border border-border-default bg-surface-overlay shadow-md outline-none sm:top-1/2 sm:left-1/2 sm:h-[min(48rem,calc(100dvh-3rem))] sm:min-h-0 sm:w-[min(56rem,calc(100vw-3rem))] sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl"
          onCloseAutoFocus={(event) => {
            const target = returnFocusRef.current?.isConnected
              ? returnFocusRef.current
              : fallbackFocusRef.current;
            if (target?.isConnected) {
              event.preventDefault();
              target.focus();
            }
            returnFocusRef.current = null;
          }}
        >
          <header className="flex shrink-0 items-start justify-between gap-4 border-b border-border-subtle px-5 py-4 sm:px-6">
            <div className="min-w-0">
              <Dialog.Title className="line-clamp-2 break-words font-heading-h3 text-content-primary">
                {detail.data?.title || selection.title}
              </Dialog.Title>
              <Dialog.Description className="mt-1 font-secondary-body text-content-muted">
                Extracted document text with the selected match highlighted.
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <Button prominence="secondary" size="sm" aria-label="Close document preview">
                <X className="size-4" aria-hidden="true" />
              </Button>
            </Dialog.Close>
          </header>

          <div
            className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 py-4 sm:px-6"
            aria-busy={detail.isPending}
          >
            {detail.isPending ? (
              <p
                role="status"
                className="py-12 text-center font-main-ui-body text-content-secondary"
              >
                Loading document context…
              </p>
            ) : detail.isError || !detail.data ? (
              <div
                role="alert"
                className="rounded-xl bg-status-danger-surface p-4 text-status-danger-content"
              >
                <p className="font-main-ui-body">
                  This document is unavailable or has changed. Search again to find its current
                  version.
                </p>
              </div>
            ) : (
              <div className="space-y-4">
                {detail.data.passages.map((passage) => {
                  const isMatch = passage.ordinal === activeMatch?.matchingOrdinal;
                  return (
                    <article
                      key={passage.ordinal}
                      ref={isMatch ? matchingPassageRef : undefined}
                      aria-current={isMatch ? "true" : undefined}
                      aria-label={isMatch ? "Selected match" : undefined}
                      className={
                        isMatch
                          ? "scroll-m-8 rounded-xl border border-border-default bg-status-info-surface p-4"
                          : "border-t border-border-subtle pt-4 first:border-0 first:pt-0"
                      }
                    >
                      {isMatch ? (
                        <p className="mb-2 font-secondary-action text-content-muted">
                          Selected match
                        </p>
                      ) : null}
                      <p className="whitespace-pre-wrap break-words font-main-content-body text-content-primary">
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
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
