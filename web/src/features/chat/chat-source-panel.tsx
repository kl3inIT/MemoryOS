import { useQuery } from "@tanstack/react-query";
import { getSearchDocument } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { stripGeneratedTitlePrefix } from "@/features/search/search-presentation";
import { useEffect, useRef, useSyncExternalStore } from "react";
import { Dialog } from "radix-ui";
import { ArrowLeft, ChevronRight, FileText, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { DocumentPreviewContent } from "@/features/search/document-preview-content";
import type { ChatSource } from "./chat-evidence";
import { ChatFileReader } from "./chat-file-reader";

const wideQuery = "(min-width: 1024px)";
function subscribeWidth(notify: () => void) {
  const query = window.matchMedia(wideQuery);
  query.addEventListener("change", notify);
  return () => query.removeEventListener("change", notify);
}

export function ChatSourcePanel({
  id,
  sources,
  citationId,
  onSelect,
  onClose,
  restoreFocus,
}: {
  id: string;
  sources: ChatSource[];
  citationId?: number;
  onSelect: (citationId?: number) => void;
  onClose: () => void;
  restoreFocus: () => void;
}) {
  const wide = useSyncExternalStore(
    subscribeWidth,
    () => window.matchMedia(wideQuery).matches,
    () => false,
  );
  const titleRef = useRef<HTMLHeadingElement>(null);
  const selected = sources.find((source) => source.citationId === citationId);
  useEffect(() => {
    titleRef.current?.focus({ preventScroll: true });
  }, [citationId, wide]);

  useEffect(() => {
    if (!wide) return undefined;
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !event.defaultPrevented) onClose();
    };
    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [wide, onClose]);

  const content = (
    <>
      <header className="flex shrink-0 items-center gap-2 border-b border-border-subtle px-4 py-3">
        {selected && (
          <IconButton
            prominence="internal"
            size="sm"
            aria-label="Back to sources"
            onClick={() => onSelect()}
          >
            <ArrowLeft />
          </IconButton>
        )}
        <h2
          ref={titleRef}
          tabIndex={-1}
          className="min-w-0 flex-1 truncate font-main-ui-action outline-none"
        >
          {selected ? "Document preview" : `Sources · ${sources.length}`}
        </h2>
        <IconButton prominence="internal" size="sm" aria-label="Close sources" onClick={onClose}>
          <X />
        </IconButton>
      </header>
      {selected ? (
        <>
          <div className="shrink-0 border-b border-border-subtle px-5 py-5">
            <p className="mb-2 flex items-center gap-1.5 text-xs text-content-muted">
              <FileText className="size-3.5" aria-hidden="true" /> Document · Source{" "}
              {selected.citationId}
            </p>
            <h3 className="break-words font-heading-h3">{selected.title}</h3>
            <p className="mt-2 text-xs leading-5 text-content-muted">
              {selected.fileId
                ? "Mở nội dung file được trích dẫn."
                : "Cited passages are highlighted."}
            </p>
          </div>
          {selected.fileId != null ? (
            <div className="min-h-0 flex-1 overflow-y-auto p-4">
              <ChatFileReader key={selected.fileId} fileId={selected.fileId} />
            </div>
          ) : (
            <DocumentPreviewContent
              key={`${selected.documentId}:${selected.generation}:${selected.citationId}`}
              variant="chat"
              selection={{
                documentId: selected.documentId,
                generation: selected.generation,
                title: selected.title,
                matches: [
                  {
                    from: Math.max(0, selected.startOrdinal - 2),
                    matchingOrdinal: selected.startOrdinal,
                    matchingEndOrdinal: selected.endOrdinal,
                  },
                ],
                activeMatchIndex: 0,
              }}
            />
          )}
        </>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4">
          <ol className="space-y-3">
            {sources.map((source) => (
              <li key={source.citationId}>
                <button
                  type="button"
                  aria-label={`Read source ${source.citationId}: ${source.title}`}
                  onClick={() => onSelect(source.citationId)}
                  className="group w-full rounded-xl border border-border-subtle bg-surface-raised p-4 text-left transition-colors hover:border-border-strong hover:bg-surface-sunken focus-visible:outline-2 focus-visible:outline-ring"
                >
                  <div className="mb-2 flex items-center gap-1.5 text-xs text-content-muted">
                    <FileText className="size-3.5" aria-hidden="true" /> Source {source.citationId}
                    <ChevronRight className="ml-auto size-3.5" aria-hidden="true" />
                  </div>
                  <h3 className="line-clamp-2 break-words font-main-ui-action">{source.title}</h3>
                  <SourceExcerpt source={source} />
                </button>
              </li>
            ))}
          </ol>
        </div>
      )}
    </>
  );

  return wide ? (
    <aside
      id={id}
      aria-label="Sources"
      className="flex h-full min-h-0 w-100 max-w-[44%] shrink-0 flex-col border-l border-border-default bg-surface-base"
    >
      {content}
    </aside>
  ) : (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-content-primary/25" />
        <Dialog.Content
          id={id}
          aria-describedby={undefined}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            restoreFocus();
          }}
          className="fixed inset-y-0 right-0 z-50 flex w-full max-w-md flex-col bg-surface-base pb-[env(safe-area-inset-bottom)] shadow-lg outline-none"
        >
          <Dialog.Title className="sr-only">
            {selected ? `Document preview: ${selected.title}` : "Sources"}
          </Dialog.Title>
          {content}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function SourceExcerpt({ source }: { source: ChatSource }) {
  if (source.fileId != null)
    return <p className="mt-2 text-sm text-content-muted">File đính kèm · Mở để đọc nội dung.</p>;
  return <DocumentSourceExcerpt source={source} />;
}

function DocumentSourceExcerpt({
  source,
}: {
  source: Extract<ChatSource, { documentId: string }>;
}) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const from = Math.max(0, source.startOrdinal - 2);
  const detail = useQuery({
    queryKey: [
      "chat-source-excerpt",
      actorId,
      authorizationVersion,
      source.documentId,
      source.generation,
      from,
    ],
    queryFn: async ({ signal }) =>
      (
        await getSearchDocument({
          path: { documentId: source.documentId },
          query: { generation: source.generation, from },
          signal,
          throwOnError: true,
        })
      ).data,
    retry: false,
    // Short-lived, authority-scoped hover data. Opening the full reader uses its
    // separate query and always revalidates the requested document generation.
    staleTime: 30_000,
    gcTime: 30_000,
  });
  const excerpt = detail.data?.passages
    .filter(
      (passage) => passage.ordinal >= source.startOrdinal && passage.ordinal <= source.endOrdinal,
    )
    .map((passage) => stripGeneratedTitlePrefix(passage.content, detail.data!.title).trim())
    .join(" ");
  return (
    <p className="mt-2 line-clamp-3 font-secondary-body leading-6 text-content-secondary">
      {detail.isPending
        ? "Loading excerpt…"
        : detail.isError
          ? "This source is unavailable or has changed."
          : excerpt || "Open this document to read the context."}
    </p>
  );
}
