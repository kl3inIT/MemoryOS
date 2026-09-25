import { useDeferredValue, useMemo, useRef, useState, type ReactNode, type RefObject } from "react";
import {
  keepPreviousData,
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { FilterChips } from "@/components/composites/filter-chips";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { TablePagination } from "@/components/ui/table-pagination";
import {
  useApplicationSession,
  useGlobalCapability,
} from "@/features/identity/application-session-context";
import { DocumentPreviewDialog } from "@/features/search/document-preview-dialog";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { actionErrorText } from "@/lib/action-errors";
import { cn } from "@/lib/utils";
import { ChatFilePreviewModal } from "./file-preview-modal";
import {
  askLibraryQuestion,
  chatLibraryKey,
  LIBRARY_CATEGORIES,
  LIBRARY_PAGE_SIZE,
  LIBRARY_PAGE_SIZES,
  type LibraryCategory,
} from "./library";
import type { LibraryChat } from "./library-chat";
import {
  downloadMeetingMinutes,
  downloadMeetingTranscript,
  entriesNamed,
  entryPreviewTarget,
  entryUpload,
  loadDocumentPage,
  loadDocumentSources,
  loadEntryPage,
  loadRecentEntries,
  meetingMinutesUpload,
  recordEntryOpened,
  setEntryStarred,
  type EntryFilter,
  type EntrySort,
  type LibraryEntry,
  type LibraryEntryKind,
  type MeetingOwner,
} from "./library-entries";
import { LibraryEntryEmpty, LibraryEntryList, type EntryActions } from "./library-entry-list";
import { categoryLabels } from "./library-labels";
import type { LibraryEntryView } from "./library-rail";
import { LibraryListSkeleton, LibraryNoMatch } from "./library-rows";
import {
  LibraryLayoutToggle,
  LibrarySearchField,
  LibrarySortSelect,
  type LibraryLayout,
} from "./library-toolbar";

/** The kinds a chip narrows to; the person's own files are one choice, whatever made them. */
const KIND_CHIPS: Record<string, LibraryEntryKind[]> = {
  OWNED: ["UPLOAD", "GENERATED", "IMAGE"],
  MEETING: ["MEETING"],
  AGENT_FILE: ["AGENT_FILE"],
  DOCUMENT: ["DOCUMENT"],
};
const ENTRY_SORTS: readonly EntrySort[] = ["NEWEST", "OLDEST", "NAME"];
/** Source documents are keyset-paged by date or by name only. */
const DOCUMENT_SORTS: readonly EntrySort[] = ["NEWEST", "NAME"];
/** Every Source, in the Source select; a Source id is a UUID, so it never collides. */
const EVERY_SOURCE = "ALL";

/**
 * The views of what the person can see or use beyond their own files: what they opened, what others share with
 * them, meetings, the organisation's documents and what they starred. Each view keeps its own filters, so the page
 * mounts one per view; the layout is the page's and follows the person across views.
 */
export function LibraryEntryViews({
  view,
  layout,
  onLayout,
  chat,
  onSaveImage,
}: {
  view: LibraryEntryView;
  layout: LibraryLayout;
  onLayout: (next: LibraryLayout) => void;
  chat?: LibraryChat;
  /** Keeps an image cropped in the preview as a new file of the person's own. */
  onSaveImage: (file: File) => void;
}) {
  const ui = useAppTranslation();
  const canReadDocuments = useGlobalCapability("SEARCH_READ");
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const [kind, setKind] = useState<string>();
  const [category, setCategory] = useState<LibraryCategory>();
  const [sort, setSort] = useState<EntrySort>("NEWEST");
  const [owner, setOwner] = useState<MeetingOwner>("ALL");
  const [sourceId, setSourceId] = useState<string>();
  const fallbackFocus = useRef<HTMLDivElement>(null);
  const { actions, dialogs } = useEntryActions(chat, onSaveImage, fallbackFocus);

  const filter = useMemo<EntryFilter>(
    () => ({
      query,
      kinds: kind ? (KIND_CHIPS[kind] ?? []) : [],
      categories: category ? [category] : [],
      sort,
      owner,
      sourceIds: sourceId ? [sourceId] : [],
    }),
    [query, kind, category, sort, owner, sourceId],
  );
  const filtered =
    query.length > 0 ||
    kind !== undefined ||
    category !== undefined ||
    owner !== "ALL" ||
    sourceId !== undefined;
  const clearFilters = () => {
    setSearch("");
    setKind(undefined);
    setCategory(undefined);
    setOwner("ALL");
    setSourceId(undefined);
  };
  const categories = categoryLabels(ui);
  const categoryChips = (
    <FilterChips
      label={ui("Loại tệp")}
      chips={LIBRARY_CATEGORIES.map((value) => ({ value, label: categories[value] }))}
      value={category}
      onChange={(next) => setCategory(next as LibraryCategory | undefined)}
    />
  );
  const results = { view, filter, filtered, layout, actions, onClearFilters: clearFilters };

  return (
    <div ref={fallbackFocus} tabIndex={-1} className="flex flex-col gap-4 outline-none">
      <div className="flex flex-wrap items-center gap-2">
        <LibrarySearchField value={search} onChange={setSearch} label={ui("Tìm theo tên")} />
        {view === "documents" && <DocumentSourceSelect value={sourceId} onChange={setSourceId} />}
        {(view === "shared" || view === "meetings") && (
          <LibrarySortSelect sort={sort} sorts={ENTRY_SORTS} onSort={setSort} />
        )}
        {view === "documents" && (
          <LibrarySortSelect sort={sort} sorts={DOCUMENT_SORTS} onSort={setSort} />
        )}
        <LibraryLayoutToggle layout={layout} onLayout={onLayout} />
      </div>
      {view === "shared" && (
        <div className="flex flex-col gap-2">
          <FilterChips
            label={ui("Loại")}
            chips={[
              { value: "MEETING", label: ui("Cuộc họp") },
              { value: "AGENT_FILE", label: ui("Tệp của trợ lý") },
            ]}
            value={kind}
            onChange={setKind}
          />
          {categoryChips}
        </div>
      )}
      {view === "meetings" && (
        <FilterChips
          label={ui("Của ai")}
          chips={[
            { value: "ALL", label: ui("Tất cả") },
            { value: "MINE", label: ui("Của tôi") },
            { value: "SHARED", label: ui("Được chia sẻ") },
          ]}
          value={owner}
          // Pressing the chosen chip clears it, which is every meeting again.
          onChange={(next) => setOwner((next ?? "ALL") as MeetingOwner)}
        />
      )}
      {view === "documents" && categoryChips}
      {view === "starred" && (
        <FilterChips
          label={ui("Loại")}
          chips={[
            { value: "OWNED", label: ui("Tệp của tôi") },
            { value: "MEETING", label: ui("Cuộc họp") },
            { value: "AGENT_FILE", label: ui("Tệp của trợ lý") },
            ...(canReadDocuments ? [{ value: "DOCUMENT", label: ui("Tài liệu tổ chức") }] : []),
          ]}
          value={kind}
          onChange={setKind}
        />
      )}

      {view === "recent" ? (
        <RecentEntries {...results} />
      ) : view === "documents" ? (
        <DocumentEntries {...results} />
      ) : (
        <PagedEntries {...results} view={view} />
      )}
      {dialogs}
    </div>
  );
}

type ResultsProps = {
  view: LibraryEntryView;
  filter: EntryFilter;
  filtered: boolean;
  layout: LibraryLayout;
  actions: EntryActions;
  onClearFilters: () => void;
};

/** What the person opened last; the server keeps at most a hundred, so the name search runs here. */
function RecentEntries(props: ResultsProps) {
  const { actorId, authorizationVersion } = useApplicationSession();
  const recent = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "entries", "recent"],
    queryFn: ({ signal }) => loadRecentEntries(signal),
    // Every visit reads the opens made since, including those made on the other views a moment ago.
    staleTime: 0,
  });
  return (
    <EntryResults
      {...props}
      state={recent}
      entries={entriesNamed(recent.data ?? [], props.filter.query)}
    />
  );
}

/** The views the server pages by offset, with the same pager and page sizes as the person's own files. */
function PagedEntries(props: ResultsProps & { view: "shared" | "meetings" | "starred" }) {
  const ui = useAppTranslation();
  const { view, filter } = props;
  const { actorId, authorizationVersion } = useApplicationSession();
  const [size, setSize] = useState<number>(LIBRARY_PAGE_SIZE);
  const [offset, setOffset] = useState(0);
  // Every filter change starts the list again: page 3 of the previous filter means nothing.
  const [pagedFilter, setPagedFilter] = useState(filter);
  if (pagedFilter !== filter) {
    setPagedFilter(filter);
    setOffset(0);
  }
  const page = useQuery({
    queryKey: [
      ...chatLibraryKey,
      actorId,
      authorizationVersion,
      "entries",
      view,
      filter,
      offset,
      size,
    ],
    queryFn: ({ signal }) => loadEntryPage(view, filter, offset, size, signal),
    // Paging keeps the page being read on screen until the next one arrives, instead of emptying the list.
    placeholderData: keepPreviousData,
  });
  const total = page.data?.totalCount ?? 0;
  return (
    <>
      <EntryResults {...props} state={page} entries={page.data?.items ?? []} />
      {page.data && total > 0 && (
        <TablePagination
          label={ui("Phân trang thư viện")}
          className="px-0"
          page={Math.floor(offset / size)}
          totalPages={Math.ceil(total / size)}
          summary={ui("Hiển thị {{first}}–{{last}} trên {{total}} mục", {
            first: offset + 1,
            last: Math.min(offset + size, total),
            total,
          })}
          previousDisabled={offset === 0}
          nextDisabled={!page.data.hasMore}
          previousLabel={ui("Trang trước")}
          nextLabel={ui("Trang sau")}
          onPrevious={() => setOffset(Math.max(0, offset - size))}
          onNext={() => setOffset(offset + size)}
        >
          <PageSizeSelect
            label={ui("Số mục mỗi trang")}
            rowsLabel={ui("Số mục")}
            value={size}
            sizes={LIBRARY_PAGE_SIZES}
            disabled={page.isPlaceholderData}
            // A page size change re-cuts the list, so it restarts at its first page.
            onSizeChange={(next) => {
              setSize(next);
              setOffset(0);
            }}
          />
        </TablePagination>
      )}
    </>
  );
}

/**
 * The organisation's documents, paged by a cursor as the Source views page them: the next page continues where
 * this one ended, so *Tải thêm* appends rather than replaces.
 */
function DocumentEntries(props: ResultsProps) {
  const ui = useAppTranslation();
  const { filter } = props;
  const { actorId, authorizationVersion } = useApplicationSession();
  const documents = useInfiniteQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "entries", "documents", filter],
    queryFn: ({ pageParam, signal }) =>
      loadDocumentPage(filter, pageParam, LIBRARY_PAGE_SIZE, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <EntryResults
        {...props}
        state={documents}
        entries={documents.data?.pages.flatMap((page) => page.items) ?? []}
      />
      {documents.hasNextPage && !documents.isPlaceholderData && (
        <Button
          size="sm"
          prominence="secondary"
          className="self-center"
          pending={documents.isFetchingNextPage}
          onClick={() => void documents.fetchNextPage()}
        >
          {ui("Tải thêm")}
        </Button>
      )}
    </>
  );
}

/** Loading, failure, nothing, or the rows, for whichever way a view is paged. */
function EntryResults({
  view,
  filtered,
  layout,
  actions,
  onClearFilters,
  state,
  entries,
}: ResultsProps & {
  state: {
    isPending: boolean;
    isError: boolean;
    isSuccess: boolean;
    isPlaceholderData: boolean;
    refetch: () => unknown;
  };
  entries: readonly LibraryEntry[];
}) {
  const ui = useAppTranslation();
  return (
    <>
      {state.isPending && <LibraryListSkeleton />}
      {state.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được thư viện.")}</AlertTitle>
          <AlertDescription>
            <Button prominence="internal" size="sm" onClick={() => void state.refetch()}>
              {ui("Thử lại")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {state.isSuccess &&
        entries.length === 0 &&
        (filtered ? (
          <LibraryNoMatch title={ui("Không có mục nào khớp")} onClearFilters={onClearFilters} />
        ) : (
          <LibraryEntryEmpty view={view} />
        ))}
      {entries.length > 0 && (
        // While the next page is on its way the one being read stays, dimmed rather than gone.
        <div
          aria-busy={state.isPlaceholderData}
          className={cn("transition-opacity", state.isPlaceholderData && "opacity-60")}
        >
          <LibraryEntryList entries={entries} view={view} layout={layout} actions={actions} />
        </div>
      )}
    </>
  );
}

/** Narrows the documents to one Source, from those Search offers the person. */
function DocumentSourceSelect({
  value,
  onChange,
}: {
  value: string | undefined;
  onChange: (next: string | undefined) => void;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const sources = useQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "document-sources"],
    queryFn: ({ signal }) => loadDocumentSources(signal),
    staleTime: 5 * 60_000,
  });
  return (
    <Select
      value={value ?? EVERY_SOURCE}
      onValueChange={(next) => onChange(next === EVERY_SOURCE ? undefined : next)}
    >
      <SelectTrigger aria-label={ui("Nguồn")} className="w-48">
        <SelectValue />
      </SelectTrigger>
      <SelectContent position="popper" align="end" sideOffset={4}>
        <SelectItem value={EVERY_SOURCE}>{ui("Tất cả nguồn")}</SelectItem>
        {sources.data?.map((source) => (
          <SelectItem key={source.id} value={source.id}>
            {source.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * What a row's commands do, and the readers they open. Every preview, download, question and opened link is
 * recorded for Gần đây. A failure is said in the application's notifications, because a reference cannot be
 * repaired from here.
 */
function useEntryActions(
  chat: LibraryChat | undefined,
  onSaveImage: (file: File) => void,
  fallbackFocus: RefObject<HTMLElement | null>,
): { actions: EntryActions; dialogs: ReactNode } {
  const cache = useQueryClient();
  const notify = useActionNotifications();
  const [previewing, setPreviewing] = useState<LibraryEntry>();
  const [reading, setReading] = useState<LibraryEntry>();
  const [pending, setPending] = useState<string>();
  const returnFocus = useRef<HTMLElement | null>(null);
  const refresh = () => cache.invalidateQueries({ queryKey: chatLibraryKey });

  /** One command at a time per row, so a slow export is not started twice. */
  const run = async (entry: LibraryEntry, work: (signal: AbortSignal) => Promise<void>) => {
    setPending(`${entry.kind}:${entry.id}`);
    recordEntryOpened(entry.kind, entry.id);
    try {
      await work(AbortSignal.timeout(120_000));
    } catch (failure) {
      notify({ title: actionErrorText(failure), tone: "error" });
    } finally {
      setPending(undefined);
    }
  };

  const star = async (entry: LibraryEntry) => {
    try {
      await setEntryStarred(entry, !entry.starred, AbortSignal.timeout(30_000));
    } catch (failure) {
      notify({ title: actionErrorText(failure), tone: "error" });
    } finally {
      // A star on an owned file is its favourite, so the person's own list follows too.
      await refresh();
    }
  };

  const actions: EntryActions = {
    pending,
    onPreview: (entry, trigger) => {
      returnFocus.current = trigger;
      recordEntryOpened(entry.kind, entry.id);
      if (entry.kind === "DOCUMENT") setReading(entry);
      else setPreviewing(entry);
    },
    onStar: (entry) => void star(entry),
    onOpened: (entry) => recordEntryOpened(entry.kind, entry.id),
    // A meeting is asked about through the person's own copy of its minutes; a file is attached as it is.
    onAsk:
      chat &&
      ((entry) =>
        void run(entry, async (signal) => {
          const attach =
            entry.kind === "MEETING" ? await meetingMinutesUpload(entry, signal) : entry.id;
          if (entry.kind === "MEETING") await refresh();
          await chat.ask({ question: "", title: entry.name, attach: [attach] }, signal);
        })),
    onMinutes: (entry) => void run(entry, (signal) => downloadMeetingMinutes(entry, signal)),
    onTranscript: (entry) => void run(entry, (signal) => downloadMeetingTranscript(entry, signal)),
  };

  const target = previewing && entryPreviewTarget(previewing);
  const generation = reading?.document?.generation;
  const dialogs = (
    <>
      {previewing && target && (
        <ChatFilePreviewModal
          target={target}
          onClose={() => setPreviewing(undefined)}
          onSaveImage={onSaveImage}
          ask={
            chat && {
              ModelPicker: chat.ModelPicker,
              onAsk: (_target, question, extras) => {
                recordEntryOpened(previewing.kind, previewing.id);
                return askLibraryQuestion(
                  chat,
                  (signal) => entryUpload(previewing, signal),
                  question,
                  extras,
                  refresh,
                );
              },
            }
          }
        />
      )}
      {reading?.document && generation && (
        <DocumentPreviewDialog
          key={`${reading.id}:${generation}`}
          selection={{
            documentId: reading.id,
            generation,
            title: reading.document.title ?? reading.name,
            mediaType: reading.mediaType,
            sourceTypes: [reading.document.sourceType],
            providerUrl: reading.document.providerUrl,
            matches: [],
            activeMatchIndex: 0,
          }}
          returnFocusRef={returnFocus}
          fallbackFocusRef={fallbackFocus}
          onClose={() => setReading(undefined)}
        />
      )}
    </>
  );
  return { actions, dialogs };
}
