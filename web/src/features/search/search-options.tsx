import { TextSearch } from "lucide-react";
import { appText, type AppCopy } from "@/i18n/app-text";
import { friendlyMediaType } from "@/features/documents/document-source-presentation";
import type { DocumentSourceType } from "@/features/documents/document-source-presentation";
import { sourceProviders } from "@/features/sources/shared/source-provider-catalog";
import type { DictationStatus } from "@/features/voice/use-dictation-input";
import type { Result as SearchResult, SearchRequest, SourceFacets } from "@/lib/hey-api/types.gen";
import type { SearchFilterOption } from "./search-filter-menu";
import type { SearchSourceOption } from "./search-source-rail";

export const FILE_TYPE_OPTIONS: readonly SearchFilterOption[] = [
  { value: "all", label: "All file types" },
  // Same friendly names as the file-type facets and result metadata.
  { value: "application/pdf", label: "PDF" },
  {
    value: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    label: "Word document",
  },
  {
    value: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    label: "PowerPoint presentation",
  },
  { value: "text/plain", label: "Text document" },
  { value: "text/markdown", label: "Markdown" },
];

export const TIME_RANGE_OPTIONS: readonly SearchFilterOption[] = [
  { value: "all", label: "All time" },
  { value: "7d", label: "Past 7 days" },
  { value: "30d", label: "Past 30 days" },
  { value: "365d", label: "Past year" },
];

export type SearchTimeRange = "all" | "7d" | "30d" | "365d";

export function updatedSinceForTimeRange(timeRange: SearchTimeRange): string | undefined {
  if (timeRange === "all") return undefined;
  const days = timeRange === "7d" ? 7 : timeRange === "30d" ? 30 : 365;
  const since = new Date();
  since.setUTCHours(0, 0, 0, 0);
  since.setUTCDate(since.getUTCDate() - days);
  return since.toISOString();
}

/** The fixed filters plus every media type on this page (and the selected one), so every result type is selectable. */
export function withResultFileTypes(
  results: readonly SearchResult[],
  selected: string | null | undefined,
): SearchFilterOption[] {
  const known = new Set(FILE_TYPE_OPTIONS.map((option) => option.value));
  const extra = [...new Set([...results.map((item) => item.mediaType), selected])]
    .filter((value): value is string => !!value && !known.has(value))
    .map((value) => ({ value, label: friendlyMediaType(value) }));
  return [...FILE_TYPE_OPTIONS, ...extra];
}

/** "All sources" and every catalog connector in catalog order; a connector without results cannot be chosen. */
export function searchSourceOptions(
  facets: SourceFacets | undefined,
  selected: DocumentSourceType | null,
): SearchSourceOption[] {
  const counts = new Map(facets?.types.map((facet) => [facet.type, facet.count]));
  return [
    {
      value: "all",
      label: "All sources",
      count: facets?.total ?? 0,
      icon: <TextSearch className="size-4 text-content-muted" />,
    },
    ...sourceProviders.map((provider) => {
      const Icon = provider.icon;
      const count = counts.get(provider.type) ?? 0;
      return {
        value: provider.type,
        // Result cards name uploads the same way.
        label: provider.type === "FILE" ? "Tệp tải lên" : provider.name,
        count,
        icon: <Icon className="size-4 text-content-muted" />,
        disabled: count === 0 && provider.type !== selected,
      };
    }),
  ];
}

/** What the live region says about the search on screen. */
export function searchStatus(
  request: SearchRequest | null,
  result: { isError: boolean; data?: { results: unknown[] } },
  isSearching: boolean,
): AppCopy {
  if (!request) return "";
  if (isSearching) return "Searching documents.";
  if (result.isError) return "Search is temporarily unavailable.";
  const count = result.data?.results.length ?? 0;
  if (count === 0) return "No matching documents.";
  return appText(
    count === 1 ? "{{count}} result on this page." : "{{count}} results on this page.",
    { count },
  );
}

export function voiceStatusMessage(status: DictationStatus) {
  if (status === "starting") return "Starting the microphone…";
  if (status === "listening") return "Listening… Speak now, then review your query.";
  if (status === "finishing") return "Finishing the transcript…";
  if (status === "failed") return "Voice search stopped unexpectedly. Please try again.";
  return "Voice search is ready.";
}
