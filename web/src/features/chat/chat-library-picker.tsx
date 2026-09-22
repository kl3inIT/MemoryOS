import { useDeferredValue, useState } from "react";
import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";
import { Check, FileText, Search, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { ClampedList } from "@/components/ui/clamped-list";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { DocumentKindIcon } from "@/features/search/document-source-icon";
import { useApplicationSession } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { cn } from "@/lib/utils";
import { ChatDialog } from "./chat-dialog";
import { fileSize } from "./chat-code";
import { LibraryCategoryFilter } from "./chat-library-toolbar";
import {
  chatLibraryKey,
  libraryThumbnailUrl,
  loadLibrary,
  LIBRARY_PAGE_SIZE,
  type LibraryCategory,
  type LibraryFile,
  type LibrarySource,
} from "./chat-library";

/**
 * "Choose an existing file" over the whole library (MEM-152): uploads, files a code run generated and generated
 * images, searchable and filtered like `/library`. The choice closes the dialog at once; anything Chat created
 * is copied into an upload beside the draft's other files, where the composer shows that wait.
 */
export function ChatLibraryPicker({
  open,
  onOpenChange,
  selected,
  preparing = 0,
  onAttach,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Uploads already on the draft; they are shown as attached and count against the 20-file limit. */
  selected: string[];
  /** Files the composer is still preparing; they hold their place in the same limit. */
  preparing?: number;
  onAttach: (files: LibraryFile[]) => void;
}) {
  const ui = useAppTranslation();
  return (
    <ChatDialog
      wide
      fill
      open={open}
      onOpenChange={onOpenChange}
      title={ui("Chọn tệp từ thư viện")}
      description={ui(
        "Tệp bạn đã tải lên, tệp do mã tạo và ảnh AI. Tệp do Chat tạo được sao chép để đính kèm.",
      )}
    >
      {open && (
        <PickerBody
          selected={selected}
          preparing={preparing}
          onAttach={(files) => {
            onAttach(files);
            onOpenChange(false);
          }}
        />
      )}
    </ChatDialog>
  );
}

function PickerBody({
  selected,
  preparing,
  onAttach,
}: {
  selected: string[];
  preparing: number;
  onAttach: (files: LibraryFile[]) => void;
}) {
  const ui = useAppTranslation();
  const { actorId, authorizationVersion } = useApplicationSession();
  const [search, setSearch] = useState("");
  const query = useDeferredValue(search.trim());
  const [source, setSource] = useState<"ALL" | LibrarySource>("ALL");
  const [categories, setCategories] = useState<LibraryCategory[]>([]);
  const [chosen, setChosen] = useState<LibraryFile[]>([]);
  const remaining = Math.max(0, 20 - selected.length - preparing);

  const filter = {
    query,
    sources: source === "ALL" ? [] : [source],
    categories,
    sort: "NEWEST" as const,
  };
  const pages = useInfiniteQuery({
    queryKey: [...chatLibraryKey, actorId, authorizationVersion, "picker", filter],
    queryFn: ({ pageParam, signal }) => loadLibrary(filter, pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (last, all) => (last.hasMore ? all.length * LIBRARY_PAGE_SIZE : undefined),
    // A filter or a search keeps the list that is being read on screen until the next one arrives, so the
    // dialog dims instead of emptying and jumping.
    placeholderData: keepPreviousData,
  });
  const files = pages.data?.pages.flatMap((page) => page.items) ?? [];
  const busy = pages.isPlaceholderData;

  const sourceLabels: Record<LibrarySource, string> = {
    UPLOAD: ui("Đã tải lên"),
    GENERATED: ui("Do mã tạo"),
    IMAGE: ui("Ảnh AI"),
  };

  const isChosen = (file: LibraryFile) =>
    chosen.some((item) => item.source === file.source && item.id === file.id);
  const toggle = (file: LibraryFile) =>
    setChosen(
      isChosen(file)
        ? chosen.filter((item) => !(item.source === file.source && item.id === file.id))
        : [...chosen, file],
    );

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={ui("Tìm theo tên tệp")}
            aria-label={ui("Tìm theo tên tệp")}
            maxLength={200}
            className="pl-9"
            autoFocus
          />
        </div>
        <LibraryCategoryFilter categories={categories} onCategories={setCategories} />
        <Tabs value={source} onValueChange={(value) => setSource(value as typeof source)}>
          <TabsList aria-label={ui("Nguồn tệp")}>
            <TabsTrigger value="ALL">{ui("Tất cả")}</TabsTrigger>
            {(["UPLOAD", "GENERATED", "IMAGE"] as const).map((value) => (
              <TabsTrigger key={value} value={value}>
                {sourceLabels[value]}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>
      </div>
      <div
        className={cn(
          "min-h-0 flex-1 overflow-y-auto rounded-lg border border-border-default transition-opacity",
          busy && "opacity-60",
        )}
        role="list"
        aria-label={ui("Tệp trong thư viện")}
        aria-busy={busy || undefined}
      >
        {pages.isPending && (
          <div
            className="flex flex-col gap-2 p-3"
            role="status"
            aria-label={ui("Đang tải thư viện…")}
          >
            {[0, 1, 2, 3, 4].map((row) => (
              <div key={row} className="flex items-center gap-3">
                <Skeleton className="size-9 shrink-0 rounded-md" />
                <div className="flex min-w-0 flex-1 flex-col gap-1.5">
                  <Skeleton className="h-3.5 w-1/2" />
                  <Skeleton className="h-3 w-1/3" />
                </div>
              </div>
            ))}
          </div>
        )}
        {pages.isError && (
          <p role="alert" className="p-4 text-sm">
            {ui("Không tải được thư viện.")}{" "}
            <Button
              type="button"
              size="sm"
              prominence="internal"
              onClick={() => void pages.refetch()}
            >
              {ui("Thử lại")}
            </Button>
          </p>
        )}
        {pages.isSuccess && files.length === 0 && (
          <Empty className="py-8">
            <EmptyHeader>
              <EmptyMedia variant="icon">
                <FileText />
              </EmptyMedia>
              <EmptyTitle>
                {query || source !== "ALL" || categories.length > 0
                  ? ui("Không có tệp nào khớp")
                  : ui("Thư viện đang trống")}
              </EmptyTitle>
              <EmptyDescription>
                {query || source !== "ALL" || categories.length > 0
                  ? ui("Hãy bỏ một vài bộ lọc hoặc đổi từ khoá tìm kiếm.")
                  : ui("Tải tệp lên hoặc để Chat tạo ra, tệp sẽ xuất hiện ở đây.")}
              </EmptyDescription>
            </EmptyHeader>
          </Empty>
        )}
        {files.map((file) => {
          const attached = file.source === "UPLOAD" && selected.includes(file.id);
          const checked = attached || isChosen(file);
          const full = !checked && chosen.length >= remaining;
          return (
            <label
              key={`${file.source}:${file.id}`}
              role="listitem"
              className={cn(
                "group/item flex items-center gap-3 border-b border-border-subtle px-3 py-2 last:border-b-0",
                attached || full ? "opacity-60" : "cursor-pointer hover:bg-surface-subtle",
                checked && !attached && "bg-surface-subtle",
              )}
            >
              <Checkbox
                aria-label={ui("Chọn {{name}}", { name: file.filename })}
                checked={checked}
                disabled={attached || full}
                onCheckedChange={() => toggle(file)}
              />
              <span className="flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-md bg-surface-sunken">
                {libraryThumbnailUrl(file) ? (
                  <img
                    src={libraryThumbnailUrl(file)}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    className="size-full object-cover"
                  />
                ) : (
                  <DocumentKindIcon
                    mediaType={file.mediaType}
                    filename={file.filename}
                    className="size-4"
                  />
                )}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate font-main-ui-action" title={file.filename}>
                  {file.filename}
                </span>
                <span className="block truncate font-secondary-body text-content-muted">
                  {[
                    sourceLabels[file.source],
                    fileSize(file.sizeBytes, i18n.language),
                    new Date(file.createdAt).toLocaleDateString(i18n.language),
                    file.sessionTitle,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </span>
              </span>
              {attached && (
                <Badge variant="secondary">
                  <Check aria-hidden="true" />
                  {ui("Đã đính kèm")}
                </Badge>
              )}
            </label>
          );
        })}
        {pages.hasNextPage && (
          <div className="p-2 text-center">
            <Button
              type="button"
              size="sm"
              prominence="internal"
              pending={pages.isFetchingNextPage}
              onClick={() => void pages.fetchNextPage()}
            >
              {ui("Tải thêm")}
            </Button>
          </div>
        )}
      </div>

      {chosen.length > 0 && (
        <ClampedList
          maxRows={2}
          label={ui("Tệp sẽ đính kèm")}
          items={chosen.map((file) => (
            <button
              key={`${file.source}:${file.id}`}
              type="button"
              onClick={() => toggle(file)}
              aria-label={ui("Bỏ chọn {{name}}", { name: file.filename })}
              className="flex h-7 max-w-56 items-center gap-1 rounded-full border border-border-subtle bg-surface-subtle px-2.5 font-secondary-body text-content-secondary outline-none hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
            >
              <span className="truncate">{file.filename}</span>
              <X className="size-3.5 shrink-0" aria-hidden="true" />
            </button>
          ))}
        />
      )}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm text-content-secondary" aria-live="polite">
          {ui("Đã chọn {{count}}/{{max}} tệp", { count: chosen.length, max: remaining })}
        </span>
        <Button type="button" disabled={chosen.length === 0} onClick={() => onAttach(chosen)}>
          {ui("Đính kèm {{count}} tệp", { count: chosen.length })}
        </Button>
      </div>
    </div>
  );
}
