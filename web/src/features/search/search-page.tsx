import { useAppTranslation } from "@/i18n/use-app-translation";
import { useLayoutEffect, useRef, useState } from "react";
import { AppShellHeader } from "@/components/app-shell/app-shell-header";
import {
  DocumentPreviewDialog,
  type DocumentSelection,
} from "@/features/documents/document-preview-dialog";
import type { DocumentSourceType } from "@/features/documents/document-source-presentation";
import { matchingProvenance } from "@/features/documents/source-provenance";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import type { Result as SearchResult, Section } from "@/lib/hey-api/types.gen";
import { cn } from "@/lib/utils";
import { SearchBox } from "./search-box";
import { SearchLanding } from "./search-landing";
import { searchSourceOptions, searchStatus } from "./search-options";
import { SearchResults } from "./search-results";
import { SearchSourceRail } from "./search-source-rail";
import { useDocumentSearch, type DocumentSearch } from "./use-document-search";

export function SearchPage() {
  const ui = useAppTranslation();
  const canSearch = useGlobalCapability("SEARCH_READ");
  if (!canSearch) {
    return (
      <>
        <AppShellHeader title={ui("Search")} />
        <section role="alert" className="mx-auto w-full max-w-5xl px-5 py-8 sm:px-8">
          <h1 className="font-heading-h2 text-content-primary">{ui("Search access denied")}</h1>
          <p className="mt-2 text-content-secondary">
            {ui(
              "Your account does not have permission to search or read documents. Ask an administrator for Basic access.",
            )}
          </p>
        </section>
      </>
    );
  }
  return <AuthorizedSearchPage />;
}

function AuthorizedSearchPage() {
  const ui = useAppTranslation();
  const search = useDocumentSearch();
  const { request, result } = search;
  const [selected, setSelected] = useState<DocumentSelection | null>(null);
  const searchInputRef = useRef<HTMLInputElement | null>(null);
  const searchFormRef = useRef<HTMLFormElement | null>(null);
  const previousSearchTopRef = useRef<number | null>(null);
  const returnFocusRef = useRef<HTMLElement | null>(null);
  const hasRequest = request !== null;
  useLayoutEffect(() => {
    // The first question moves the box from the middle of the page to its top; the move is animated from where
    // the box was.
    const previousTop = previousSearchTopRef.current;
    const form = searchFormRef.current;
    previousSearchTopRef.current = null;
    if (
      previousTop === null ||
      !form ||
      typeof form.animate !== "function" ||
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
    ) {
      return;
    }
    const offset = previousTop - form.getBoundingClientRect().top;
    if (Math.abs(offset) < 1) return;
    const animation = form.animate(
      [{ transform: `translateY(${offset}px)` }, { transform: "translateY(0)" }],
      { duration: 320, easing: "cubic-bezier(0.22, 1, 0.36, 1)" },
    );
    return () => animation.cancel();
  }, [hasRequest]);

  function submit(text = search.query) {
    if (!text.trim()) return;
    if (!request) {
      previousSearchTopRef.current = searchFormRef.current?.getBoundingClientRect().top ?? null;
    }
    setSelected(null);
    search.submit(text);
  }

  function openDocument(
    item: SearchResult,
    section: Section | undefined,
    trigger: HTMLButtonElement,
  ) {
    if (!item.documentId || !item.generation) return;
    returnFocusRef.current = trigger;
    setSelected({
      documentId: item.documentId,
      generation: item.generation,
      title: item.title || "Untitled document",
      mediaType: item.mediaType,
      sourceTypes: item.sourceTypes,
      providerUrl: item.providerUrl,
      matches: item.sections.map((candidate) => ({
        matchingOrdinal: candidate.matchingOrdinal,
        from: Math.max(0, candidate.matchingOrdinal - 1),
        provenance: matchingProvenance(candidate.provenance, candidate.matchingOrdinal),
      })),
      activeMatchIndex: Math.max(
        0,
        item.sections.findIndex((candidate) => candidate === section),
      ),
    });
  }

  // A filter or another page replaces the document being read.
  const refine: DocumentSearch = {
    ...search,
    setFilter: (filter) => {
      setSelected(null);
      search.setFilter(filter);
    },
    clearFilters: () => {
      setSelected(null);
      search.clearFilters();
    },
    showPage: (page) => {
      setSelected(null);
      search.showPage(page);
    },
  };
  const sourceOptions = searchSourceOptions(result.data?.sourceFacets, search.sourceType);

  return (
    <>
      <AppShellHeader title={ui("Search documents")} />
      <section
        className={cn(
          "mx-auto w-full max-w-4xl px-5 py-5 sm:px-8 sm:py-7",
          // The rail adds its own width; the results column keeps the readable measure.
          request ? "lg:max-w-284" : "flex min-h-full flex-col",
        )}
      >
        <h1 className="sr-only">{ui("Search documents")}</h1>
        <div className={cn(!request && "my-auto w-full max-w-3xl self-center pb-16")}>
          {!request ? (
            <header className="mb-6">
              <h2 className="font-heading-h2 text-content-primary">
                {ui("Search your workspace")}
              </h2>
            </header>
          ) : null}
          <SearchBox
            search={refine}
            sourceOptions={sourceOptions}
            inputRef={searchInputRef}
            formRef={searchFormRef}
            onSubmit={() => submit()}
          />
          {!request ? (
            <SearchLanding search={refine} inputRef={searchInputRef} onAsk={submit} />
          ) : null}
        </div>

        <p role="status" aria-live="polite" className="sr-only">
          {ui(searchStatus(request, result, result.isFetching))}
        </p>

        {/* The rail is shown for every search, so filters never move the search box or the results column. */}
        <div className={cn("mt-6", request && "lg:flex lg:items-start lg:gap-8")}>
          {request ? (
            <SearchSourceRail
              options={sourceOptions}
              value={search.sourceType ?? "all"}
              busy={result.isFetching}
              onChange={(value) =>
                refine.setFilter({
                  source: value === "all" ? undefined : (value as DocumentSourceType),
                })
              }
              className="hidden lg:sticky lg:top-4 lg:block lg:w-52 lg:shrink-0"
            />
          ) : null}
          <div className="min-w-0 lg:flex-1" aria-busy={result.isFetching}>
            <SearchResults search={refine} onOpen={openDocument} />
          </div>
        </div>
      </section>

      {selected ? (
        <DocumentPreviewDialog
          key={`${selected.documentId}:${selected.generation}`}
          selection={selected}
          returnFocusRef={returnFocusRef}
          fallbackFocusRef={searchInputRef}
          onClose={() => setSelected(null)}
        />
      ) : null}
    </>
  );
}
