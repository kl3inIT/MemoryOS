import { useAppTranslation } from "@/i18n/use-app-translation";
import { Clock3, FileStack, Files, Mic, Search, SlidersHorizontal, X } from "lucide-react";
import type { RefObject } from "react";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { InputGroupInput } from "@/components/ui/input-group";
import type { DocumentSourceType } from "@/features/documents/document-source-presentation";
import { useVoiceAvailability } from "@/features/voice/use-voice-availability";
import { useDictationInput } from "@/features/voice/use-dictation-input";
import { cn } from "@/lib/utils";
import { SearchFilterMenu, type SearchFilterOption } from "./search-filter-menu";
import {
  TIME_RANGE_OPTIONS,
  voiceStatusMessage,
  withResultFileTypes,
  type SearchTimeRange,
} from "./search-options";
import type { SearchSourceOption } from "./search-source-rail";
import type { DocumentSearch } from "./use-document-search";

/**
 * The search box: the question, clearing it, dictating it and asking it, and once a question is on screen the
 * compact filters under it. Dictation uses the Tenant's speech-to-text provider through MemoryOS, never browser
 * recognition.
 */
export function SearchBox({
  search,
  sourceOptions,
  inputRef,
  formRef,
  onSubmit,
}: {
  search: DocumentSearch;
  sourceOptions: readonly SearchSourceOption[];
  inputRef: RefObject<HTMLInputElement | null>;
  formRef: RefObject<HTMLFormElement | null>;
  onSubmit: () => void;
}) {
  const ui = useAppTranslation();
  const { query, setQuery, request, result } = search;
  const voiceAvailable = useVoiceAvailability().data?.sttAvailable === true;
  const dictation = useDictationInput({
    text: query,
    onText: setQuery,
    onFinished: () => inputRef.current?.focus(),
  });
  const updating = result.isFetching;

  return (
    <form
      ref={formRef}
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      className={cn(
        "rounded-2xl border border-border-default bg-surface-raised transition duration-200 focus-within:border-border-strong motion-reduce:transition-none",
        request ? "shadow-xs" : "shadow-sm focus-within:shadow-md",
      )}
    >
      <div className="flex min-w-0 items-center gap-0.5 py-1.5 pr-1.5 pl-1">
        <InputGroupInput
          ref={inputRef}
          size="lg"
          aria-label={ui("Search documents")}
          value={query}
          maxLength={1000}
          placeholder={ui("Search connected sources")}
          className="min-w-0"
          onChange={(event) => setQuery(event.target.value)}
        />
        {query ? (
          <IconButton
            type="button"
            size="sm"
            prominence="internal"
            aria-label={ui("Clear search")}
            onClick={() => {
              setQuery("");
              inputRef.current?.focus();
            }}
          >
            <X />
          </IconButton>
        ) : null}
        {voiceAvailable ? (
          <IconButton
            type="button"
            size="lg"
            prominence="internal"
            tone={dictation.listening ? "danger" : "default"}
            aria-label={dictation.listening ? ui("Stop voice search") : ui("Search by voice")}
            aria-pressed={dictation.listening}
            aria-describedby="voice-search-status"
            pending={dictation.status === "starting" || dictation.status === "finishing"}
            title={dictation.listening ? ui("Stop listening") : ui("Search by voice")}
            onClick={() => void dictation.toggle()}
          >
            <Mic
              className={cn(dictation.listening && "animate-pulse motion-reduce:animate-none")}
            />
          </IconButton>
        ) : null}
        {updating ? (
          <IconButton
            type="button"
            size="lg"
            aria-label={ui("Search is loading")}
            title={ui("Searching documents")}
            pending
          >
            <Search />
          </IconButton>
        ) : (
          <IconButton type="submit" size="lg" aria-label={ui("Search")} disabled={!query.trim()}>
            <Search />
          </IconButton>
        )}
      </div>

      {request ? <SearchFilters search={search} sourceOptions={sourceOptions} /> : null}
      {voiceAvailable ? (
        <p
          id="voice-search-status"
          aria-live="polite"
          className={cn(
            "px-4 pb-2 font-secondary-body",
            dictation.status === "idle" && "sr-only",
            dictation.status === "failed" ? "text-status-danger-content" : "text-content-secondary",
          )}
        >
          {ui(dictation.failure ?? voiceStatusMessage(dictation.status))}
        </p>
      ) : null}
    </form>
  );
}

/** The filters of the question on screen, each a compact menu, and the way back to none. */
function SearchFilters({
  search,
  sourceOptions,
}: {
  search: DocumentSearch;
  sourceOptions: readonly SearchSourceOption[];
}) {
  const ui = useAppTranslation();
  const { mediaType, sourceType, timeRange, documentSetId, setFilter } = search;
  const documentSetOptions: SearchFilterOption[] = [
    { value: "all", label: ui("Tất cả bộ tài liệu") },
    ...search.documentSets.map((set) => ({ value: set.id, label: set.name })),
  ];
  return (
    <div className="flex animate-in flex-wrap items-center gap-2 border-t border-border-subtle px-2 py-2 duration-200 fade-in slide-in-from-top-1 motion-reduce:animate-none">
      <span className="inline-flex h-8 items-center gap-2 px-2 font-secondary-action text-content-muted">
        <SlidersHorizontal className="size-3.5" aria-hidden="true" />
        {ui("Filters")}
      </span>
      <SearchFilterMenu
        label={ui("Updated")}
        value={timeRange}
        options={TIME_RANGE_OPTIONS}
        icon={<Clock3 />}
        onChange={(value) =>
          setFilter({
            time: value === "all" ? undefined : (value as Exclude<SearchTimeRange, "all">),
          })
        }
      />
      <SearchFilterMenu
        label={ui("File type")}
        value={mediaType ?? "all"}
        options={withResultFileTypes(search.result.data?.results ?? [], mediaType)}
        icon={<FileStack />}
        onChange={(value) => setFilter({ type: value === "all" ? undefined : value })}
      />
      <SearchFilterMenu
        label={ui("Bộ tài liệu")}
        value={documentSetId ?? "all"}
        options={documentSetOptions}
        icon={<Files />}
        onChange={(value) => setFilter({ set: value === "all" ? undefined : value })}
      />
      {/* Large screens use the rail beside the results instead. */}
      <SearchFilterMenu
        label={ui("Source")}
        value={sourceType ?? "all"}
        options={sourceOptions}
        icon={<Files />}
        className="lg:hidden"
        onChange={(value) =>
          setFilter({ source: value === "all" ? undefined : (value as DocumentSourceType) })
        }
      />
      {search.hasFilters ? (
        <Button
          type="button"
          size="sm"
          prominence="tertiary"
          className="ml-auto"
          onClick={search.clearFilters}
        >
          {ui("Clear filters")}
        </Button>
      ) : null}
    </div>
  );
}
