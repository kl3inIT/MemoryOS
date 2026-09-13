import { useQuery } from "@tanstack/react-query";
import { getSearchDocument } from "@/lib/hey-api/sdk.gen";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { stripGeneratedTitlePrefix } from "@/features/search/search-presentation";
import { useEffect, useRef, useSyncExternalStore } from "react";
import { Dialog } from "radix-ui";
import { ArrowLeft, ExternalLink, FileText, X } from "lucide-react";
import { IconButton } from "@/components/ui/icon-button";
import { DocumentPreviewContent } from "@/features/search/document-preview-content";
import type { ChatSource } from "./chat-evidence";
import { ChatFileReader } from "./chat-file-reader";
import { useTranslation } from "react-i18next";
import type { ChatArtifact } from "./chat-artifacts";
import { ChatArtifactView } from "./chat-artifact-view";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { DocumentReference } from "@/components/assistant-ui/elements/document-reference";
import { SourceIcon } from "@/components/assistant-ui/elements/source-icon";

const wideQuery = "(min-width: 1024px)";
function subscribeWidth(notify: () => void) {
  const query = window.matchMedia(wideQuery);
  query.addEventListener("change", notify);
  return () => query.removeEventListener("change", notify);
}

export function ChatSourcePanel({
  id,
  sources,
  file,
  artifact,
  citationId,
  onSelect,
  onClose,
  restoreFocus,
}: {
  id: string;
  sources: ChatSource[];
  file?: { id: string; filename: string };
  artifact?: ChatArtifact;
  citationId?: number;
  onSelect: (citationId?: number) => void;
  onClose: () => void;
  restoreFocus: () => void;
}) {
  const ui = useAppTranslation();
  const { t, i18n } = useTranslation("reader");
  const { t: rendererText } = useTranslation("renderers");
  const wide = useSyncExternalStore(
    subscribeWidth,
    () => window.matchMedia(wideQuery).matches,
    () => false,
  );
  const titleRef = useRef<HTMLHeadingElement>(null);
  const selected = sources.find((source) => source.citationId === citationId);
  useEffect(() => {
    titleRef.current?.focus({ preventScroll: true });
  }, [citationId, file?.id, artifact?.id, wide]);

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
            aria-label={t("back")}
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
          {artifact ? (
            <span title={artifact.title}>{artifact.title}</span>
          ) : file ? (
            <span title={file.filename}>{file.filename}</span>
          ) : selected?.web ? (
            ui("Nội dung trang Web")
          ) : selected ? (
            t("content")
          ) : (
            t("sourcesCount", {
              total: new Intl.NumberFormat(i18n.resolvedLanguage).format(sources.length),
            })
          )}
        </h2>
        <IconButton
          prominence="internal"
          size="sm"
          aria-label={
            artifact ? rendererText("closeArtifact") : file ? t("closeFile") : t("closeSources")
          }
          onClick={onClose}
        >
          <X />
        </IconButton>
      </header>
      {artifact ? (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4">
          <ChatArtifactView artifact={artifact} />
        </div>
      ) : file ? (
        <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-4">
          <ChatFileReader key={file.id} fileId={file.id} />
        </div>
      ) : selected?.web ? (
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-5">
          <DocumentReference
            title={selected.title}
            subtitle={new URL(selected.web.url).hostname}
            icon={<SourceIcon domain={new URL(selected.web.url).hostname} className="size-4" />}
            anchors={[]}
          />
          <a
            className="inline-flex max-w-full items-center gap-2 rounded-lg border border-border-subtle px-3 py-2 text-sm hover:bg-surface-sunken focus-visible:outline-2 focus-visible:outline-ring"
            href={selected.web.url}
            target="_blank"
            rel="noopener noreferrer"
          >
            <ExternalLink className="size-4 shrink-0" aria-hidden="true" />
            {ui("Mở trang gốc")} ·{" "}
            <span className="truncate">{new URL(selected.web.url).hostname}</span>
          </a>
          <blockquote className="whitespace-pre-wrap break-words border-l-2 border-border-default pl-4 text-sm leading-7 text-content-secondary">
            {selected.web.excerpt}
          </blockquote>
        </div>
      ) : selected ? (
        <>
          <div className="shrink-0 border-b border-border-subtle px-5 py-5">
            <p className="mb-2 flex items-center gap-1.5 text-xs text-content-muted">
              <FileText className="size-3.5" aria-hidden="true" />{" "}
              {t("documentSource", { number: selected.citationId })}
            </p>
            <h3 className="break-words font-heading-h3">{selected.title}</h3>
            <p className="mt-2 text-xs leading-5 text-content-muted">
              {selected.fileId ? t("fileCitation") : t("highlighted")}
            </p>
          </div>
          {selected.fileId != null && !selected.fileLocation?.generation ? (
            <div className="min-h-0 flex-1 overflow-y-auto p-4">
              <ChatFileReader
                key={`${selected.fileId}:${selected.citationId}`}
                fileId={selected.fileId}
                initialOffset={selected.fileLocation?.offset ?? 0}
                citationCount={selected.fileLocation?.count ?? undefined}
              />
            </div>
          ) : (
            <DocumentPreviewContent
              key={`${selected.documentId}:${selected.generation}:${selected.citationId}`}
              variant="chat"
              fileId={selected.fileId ?? undefined}
              selection={{
                documentId: selected.documentId ?? selected.fileId!,
                generation: selected.generation ?? selected.fileLocation!.generation!,
                title: selected.title,
                matches: [
                  {
                    from: Math.max(
                      0,
                      (selected.fileLocation?.ordinal ?? selected.startOrdinal) - 2,
                    ),
                    matchingOrdinal: selected.fileLocation?.ordinal ?? selected.startOrdinal,
                    matchingEndOrdinal: selected.fileLocation?.ordinal ?? selected.endOrdinal,
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
                <DocumentReference
                  title={source.title}
                  subtitle={
                    source.web
                      ? new URL(source.web.url).hostname
                      : t("documentSource", { number: source.citationId })
                  }
                  icon={
                    source.web ? (
                      <SourceIcon domain={new URL(source.web.url).hostname} className="size-4" />
                    ) : undefined
                  }
                  anchors={[
                    {
                      id: source.citationId,
                      label: t("sourceNumber", { number: source.citationId }),
                      accessibleLabel: t("readSource", {
                        number: source.citationId,
                        title: source.title,
                      }),
                      quote: <SourceExcerpt source={source} />,
                    },
                  ]}
                  onJump={onSelect}
                />
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
      aria-label={artifact ? rendererText("artifact") : file ? t("content") : t("sources")}
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
            {artifact
              ? artifact.title
              : file
                ? file.filename
                : selected?.web
                  ? ui("Nội dung trang Web: {{title}}", { title: selected.title })
                  : selected
                    ? t("documentTitle", { title: selected.title })
                    : t("sources")}
          </Dialog.Title>
          {content}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function SourceExcerpt({ source }: { source: ChatSource }) {
  const { t } = useTranslation("reader");
  if (source.web)
    return (
      <p className="mt-2 line-clamp-3 break-words text-sm text-content-muted">
        {source.web.excerpt || new URL(source.web.url).hostname}
      </p>
    );
  if (source.fileId != null)
    return (
      <p className="mt-2 text-sm text-content-muted">
        {source.fileLocation ? t("fileExcerpt") : t("wholeFile")}
      </p>
    );
  return <DocumentSourceExcerpt source={source} />;
}

function DocumentSourceExcerpt({
  source,
}: {
  source: Extract<ChatSource, { documentId: string }>;
}) {
  const { t } = useTranslation("reader");
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
        ? t("loadingExcerpt")
        : detail.isError
          ? t("unavailable")
          : excerpt || t("openContext")}
    </p>
  );
}
