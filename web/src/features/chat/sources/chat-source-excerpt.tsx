import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { stripGeneratedTitlePrefix } from "@/features/documents/passage";
import { readChatDocumentPassages } from "@/lib/hey-api/sdk.gen";
import { cn } from "@/lib/utils";
import type { ChatSource } from "./chat-evidence";

/** Cited text of a source as an inline block, so it can sit inside buttons and hover cards alike. */
export function SourceExcerpt({ source, className }: { source: ChatSource; className?: string }) {
  const { t } = useTranslation("reader");
  const style = cn("block break-words text-sm leading-5 text-content-secondary", className);
  if (source.web)
    return <span className={style}>{source.web.excerpt || new URL(source.web.url).hostname}</span>;
  if (source.fileId != null)
    return <span className={style}>{source.fileLocation ? t("fileExcerpt") : t("wholeFile")}</span>;
  return <DocumentSourceExcerpt source={source} className={style} />;
}

function DocumentSourceExcerpt({
  source,
  className,
}: {
  source: Extract<ChatSource, { documentId: string }>;
  className: string;
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
        await readChatDocumentPassages({
          path: { documentId: source.documentId },
          query: { generation: source.generation, from },
          signal,
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
    <span className={className}>
      {detail.isPending
        ? t("loadingExcerpt")
        : detail.isError
          ? t("unavailable")
          : excerpt || t("openContext")}
    </span>
  );
}
