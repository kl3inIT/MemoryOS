import { useEffect, useMemo, useRef, useState, type ReactNode, type RefObject } from "react";
import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { FilterChips } from "@/components/composites/filter-chips";
import { useActionNotifications } from "@/components/ui/action-notifications";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ListFilter } from "lucide-react";
import { TooltipIconButton } from "@/components/composites/tooltip-icon-button";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { usePointerOpenedTrigger } from "@/hooks/use-pointer-opened-trigger";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { DocumentPreviewDialog } from "@/features/documents/document-preview-dialog";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { actionErrorText } from "@/lib/action-errors";
import {
  listChatLibraryDocumentSourcesOptions,
  listChatLibraryRecentOptions,
  starChatLibraryEntryMutation,
  unstarChatLibraryEntryMutation,
} from "@/lib/hey-api/@tanstack/react-query.gen";
import { cn } from "@/lib/utils";
import { ChatFilePreviewModal } from "./file-preview-modal";
import {
  askLibraryQuestion,
  invalidateLibrary,
  LIBRARY_CATEGORIES,
  LIBRARY_PAGE_SIZE,
  LISTING_LIMIT,
  type LibraryCategory,
  type LibrarySort,
} from "./library";
import type { LibraryChat } from "./library-chat";
import {
  documentPagesOptions,
  downloadMeetingMinutes,
  downloadMeetingTranscript,
  entriesNamed,
  entryPageOptions,
  entryPreviewTarget,
  entryUpload,
  meetingMinutesUpload,
  RECENT_LIMIT,
  recordEntryOpened,
  type EntryFilter,
  type EntrySort,
  type LibraryEntry,
  type LibraryEntryKind,
} from "./library-entries";
import { LibraryEntryEmpty, LibraryEntryList, type EntryActions } from "./library-entry-list";
import { categoryLabels } from "./library-labels";
import type { LibraryEntryView } from "./library-views";
import { LibraryListSkeleton, LibraryPageSizeSelect, LibraryPager } from "./library-results";
import { LibraryNoMatch } from "./library-rows";
import type { EntryKindChip } from "./library-search";
import { LibraryDisplayControls, LibrarySearchField, type LibraryLayout } from "./library-toolbar";
import type { LibraryViewState } from "./use-library-view";

/** The kinds a chip narrows to; the person's own files are one choice, whatever made them. */
const KIND_CHIPS: Record<EntryKindChip, LibraryEntryKind[]> = {
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
 * them, the organisation's documents and what they starred. Their search, filters, order and page live in the
 * library's address as the owned views' do; the layout is the page's and follows the person across views.
 */
export function LibraryEntryViews({
  view,
  library,
  layout,
  onLayout,
  chat,
  onSaveImage,
}: {
  view: LibraryEntryView;
  library: LibraryViewState;
  layout: LibraryLayout;
  onLayout: (next: LibraryLayout) => void;
  chat?: LibraryChat;
  /** Keeps an image cropped in the preview as a new file of the person's own. */
  onSaveImage: (file: File) => void;
}) {
  const ui = useAppTranslation();
  const canReadDocuments = useGlobalCapability("SEARCH_READ");
  const { query, kind, sourceId, filterBy } = library;
  const category = library.categories[0];
  const sorts = view === "documents" ? DOCUMENT_SORTS : ENTRY_SORTS;
  // A link may carry an order this view does not offer, such as the owned files' largest first.
  const sort = sorts.find((offered) => offered === library.sort) ?? "NEWEST";
  const fallbackFocus = useRef<HTMLDivElement>(null);
  const { actions, dialogs } = useEntryActions(chat, onSaveImage, fallbackFocus);

  const filter = useMemo<EntryFilter>(
    () => ({
      query,
      kinds: kind ? KIND_CHIPS[kind] : [],
      categories: category ? [category] : [],
      sort,
      sourceIds: sourceId ? [sourceId] : [],
    }),
    [query, kind, category, sort, sourceId],
  );
  const categories = categoryLabels(ui);
  const categoryGroup = {
    label: ui("Loại tệp"),
    chips: LIBRARY_CATEGORIES.map((value) => ({ value, label: categories[value] })),
    value: category,
    onChange: (next: string | undefined) =>
      filterBy({ category: next ? [next as LibraryCategory] : [] }),
  };
  const kindGroup = (chips: { value: string; label: string }[]) => ({
    label: ui("Loại"),
    chips,
    value: kind,
    onChange: (next: string | undefined) => filterBy({ kind: next as EntryKindChip | undefined }),
  });
  const groups =
    view === "shared"
      ? [
          kindGroup([
            { value: "MEETING", label: ui("Cuộc họp") },
            { value: "AGENT_FILE", label: ui("Tệp của trợ lý") },
          ]),
          categoryGroup,
        ]
      : view === "documents"
        ? [categoryGroup]
        : view === "starred"
          ? [
              kindGroup([
                { value: "OWNED", label: ui("Tệp của tôi") },
                { value: "MEETING", label: ui("Cuộc họp") },
                { value: "AGENT_FILE", label: ui("Tệp của trợ lý") },
                ...(canReadDocuments ? [{ value: "DOCUMENT", label: ui("Tài liệu tổ chức") }] : []),
              ]),
            ]
          : [];
  const results = {
    view,
    filter,
    filtered: library.filtered,
    layout,
    actions,
    onClearFilters: library.clearFilters,
  };

  return (
    <div ref={fallbackFocus} tabIndex={-1} className="flex flex-col gap-4 outline-none">
      <div className="flex flex-wrap items-center gap-2">
        <LibrarySearchField
          value={library.search}
          onChange={library.setSearch}
          label={ui("Tìm theo tên")}
        />
        {groups.length > 0 && (
          <EntryFilterMenu
            groups={groups}
            sourceId={view === "documents" ? { value: sourceId } : undefined}
            onSourceId={(next) => filterBy({ sourceId: next })}
            onClear={() => filterBy({ kind: undefined, category: [], sourceId: undefined })}
          />
        )}
        <LibraryDisplayControls
          sort={view === "shared" || view === "documents" ? sort : undefined}
          sorts={sorts}
          onSort={(next: LibrarySort) => filterBy({ sort: next })}
          layout={layout}
          onLayout={onLayout}
        />
      </div>
      {view === "recent" ? (
        <RecentEntries {...results} library={library} />
      ) : view === "documents" ? (
        <DocumentEntries {...results} library={library} />
      ) : (
        <PagedEntries {...results} view={view} library={library} />
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

/**
 * What the person opened last; the server keeps at most a hundred, so the name search and the pages are cut here.
 */
function RecentEntries({ library, ...props }: ResultsProps & { library: LibraryViewState }) {
  const recent = useQuery({
    ...listChatLibraryRecentOptions({ query: { limit: RECENT_LIMIT } }),
    // Every visit reads the opens made since, including those made on the other views a moment ago.
    staleTime: 0,
  });
  const { offset, size } = library;
  const named = entriesNamed(recent.data ?? [], props.filter.query);
  const end = size === "all" ? named.length : offset + size;
  return (
    <>
      <EntryResults {...props} state={recent} entries={named.slice(offset, end)} />
      {recent.data && (
        <LibraryPager
          library={library}
          noun="item"
          total={named.length}
          hasMore={end < named.length}
          busy={false}
        />
      )}
    </>
  );
}

/** The views the server pages by offset, with the same pager and page sizes as the person's own files. */
function PagedEntries({
  library,
  ...props
}: ResultsProps & { view: "shared" | "starred"; library: LibraryViewState }) {
  const { offset, size } = library;
  const page = useQuery({
    ...entryPageOptions(props.view, props.filter, offset, size),
    // Paging keeps the page being read on screen until the next one arrives, instead of emptying the list.
    placeholderData: keepPreviousData,
  });
  return (
    <>
      <EntryResults {...props} state={page} entries={page.data?.items ?? []} />
      {page.data && (
        <LibraryPager
          library={library}
          noun="item"
          total={page.data.totalCount}
          hasMore={page.data.hasMore}
          busy={page.isPlaceholderData}
        />
      )}
    </>
  );
}

/**
 * The organisation's documents, paged by a cursor as the Source views page them: the next page continues where
 * this one ended, so *Tải thêm* appends rather than replaces. With no total to count, the page size is offered
 * once the documents outgrow one default page; the whole list is read one page after another.
 */
function DocumentEntries({ library, ...props }: ResultsProps & { library: LibraryViewState }) {
  const ui = useAppTranslation();
  const all = library.size === "all";
  const documents = useInfiniteQuery({
    ...documentPagesOptions(props.filter, library.size === "all" ? LISTING_LIMIT : library.size),
    initialPageParam: undefined as unknown as string,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    placeholderData: keepPreviousData,
  });
  const { hasNextPage, isFetchingNextPage, isPlaceholderData, fetchNextPage } = documents;
  useEffect(() => {
    if (all && hasNextPage && !isFetchingNextPage && !isPlaceholderData) void fetchNextPage();
  }, [all, hasNextPage, isFetchingNextPage, isPlaceholderData, fetchNextPage]);
  const entries = documents.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <>
      <EntryResults {...props} state={documents} entries={entries} />
      {documents.data && (entries.length > LIBRARY_PAGE_SIZE || hasNextPage) && (
        <div className="flex flex-wrap items-center justify-center gap-3">
          <LibraryPageSizeSelect library={library} noun="item" disabled={isPlaceholderData} />
          {hasNextPage && !all && !isPlaceholderData && (
            <Button
              size="sm"
              prominence="secondary"
              pending={isFetchingNextPage}
              onClick={() => void fetchNextPage()}
            >
              {ui("Tải thêm")}
            </Button>
          )}
        </div>
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

type EntryFilterGroup = {
  label: string;
  chips: { value: string; label: string }[];
  value: string | undefined;
  onChange: (next: string | undefined) => void;
};

/**
 * The filters of a reference view behind one icon, as the owned files' filter is, with how many are on beside it:
 * what kind of entry it is, what kind of file, and for the documents which Source they come from.
 */
function EntryFilterMenu({
  groups,
  sourceId,
  onSourceId,
  onClear,
}: {
  groups: EntryFilterGroup[];
  /** Present on the documents view, which can also narrow to one Source. */
  sourceId?: { value: string | undefined };
  onSourceId: (next: string | undefined) => void;
  onClear: () => void;
}) {
  const ui = useAppTranslation();
  const pointer = usePointerOpenedTrigger();
  const active = groups.filter((group) => group.value).length + (sourceId?.value ? 1 : 0);
  return (
    <Popover>
      <PopoverTrigger asChild>
        <TooltipIconButton
          prominence="secondary"
          aria-label={ui("Bộ lọc")}
          className={active > 0 ? "w-auto px-2" : undefined}
          {...pointer.trigger}
        >
          <ListFilter />
          {active > 0 && (
            <Badge variant="secondary" className="ml-1.5">
              {active}
            </Badge>
          )}
        </TooltipIconButton>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-80" {...pointer.content}>
        <div className="flex flex-col gap-3">
          {sourceId && (
            <fieldset className="flex flex-col gap-2">
              <legend className="font-secondary-body text-content-muted">{ui("Nguồn")}</legend>
              <DocumentSourceSelect value={sourceId.value} onChange={onSourceId} />
            </fieldset>
          )}
          {groups.map((group) => (
            <fieldset key={group.label} className="flex flex-col gap-2">
              <legend className="font-secondary-body text-content-muted">{group.label}</legend>
              <FilterChips
                label={group.label}
                chips={group.chips}
                value={group.value}
                onChange={group.onChange}
              />
            </fieldset>
          ))}
          {active > 0 && (
            <Button size="sm" prominence="internal" className="self-start" onClick={onClear}>
              {ui("Xóa bộ lọc")}
            </Button>
          )}
        </div>
      </PopoverContent>
    </Popover>
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
  const sources = useQuery({
    ...listChatLibraryDocumentSourcesOptions(),
    staleTime: 5 * 60_000,
  });
  return (
    <Select
      value={value ?? EVERY_SOURCE}
      onValueChange={(next) => onChange(next === EVERY_SOURCE ? undefined : next)}
    >
      <SelectTrigger aria-label={ui("Nguồn")} focus="quiet" className="w-full">
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
  const refresh = () => invalidateLibrary(cache);
  const starEntry = useMutation(starChatLibraryEntryMutation());
  const unstarEntry = useMutation(unstarChatLibraryEntryMutation());

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
    const change = entry.starred ? unstarEntry : starEntry;
    try {
      await change.mutateAsync({
        path: { kind: entry.kind, id: entry.id },
        signal: AbortSignal.timeout(30_000),
      });
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
