import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { Skeleton } from "@/components/ui/skeleton";
import { TablePagination } from "@/components/ui/table-pagination";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import {
  LIBRARY_PAGE_SIZE,
  LIBRARY_PAGE_SIZES,
  type ContentMatch,
  type LibraryFile,
  type LibraryPageSize,
} from "./library";
import { LibraryContentMatches } from "./library-content";
import { FileActions, LibraryEmpty, LibraryList, type RowActions } from "./library-rows";
import type { LibraryOwnedView } from "./library-views";
import type { LibraryLayout } from "./library-toolbar";
import type { LibraryViewState } from "./use-library-view";

/**
 * The files of the page being read: a header whose checkbox chooses the whole page, the list or grid, and the
 * pager once the files outgrow one default page.
 */
export function LibraryFiles({
  library,
  view,
  layout,
  actions,
  emptyAction,
}: {
  library: LibraryViewState;
  /** The view of the person's own files on screen; the other views list their rows themselves. */
  view: LibraryOwnedView;
  layout: LibraryLayout;
  actions: RowActions;
  /** The upload control an empty library offers, which owns the browser's file dialog. */
  emptyAction: ReactNode;
}) {
  const ui = useAppTranslation();
  const { page, files, selected, setSelected, sort } = library;
  return (
    <>
      {page.isPending && <LibraryListSkeleton />}
      {page.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tải được thư viện.")}</AlertTitle>
          <AlertDescription>
            <Button prominence="internal" size="sm" onClick={() => void page.refetch()}>
              {ui("Thử lại")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {page.isSuccess && files.length === 0 && (
        <LibraryEmpty
          view={view}
          filtered={library.filtered}
          action={emptyAction}
          onClearFilters={library.clearFilters}
        />
      )}
      {files.length > 0 && (
        // While the next page is on its way the one being read stays, dimmed rather than gone.
        <div
          aria-busy={page.isPlaceholderData}
          className={cn(
            "flex flex-col gap-4 transition-opacity",
            page.isPlaceholderData && "opacity-60",
          )}
        >
          {/* A quiet list header in the voice of the day headings below it, aligned to the rows' own checkbox
              column so the three checkbox gutters (page, day, file) read as one line down the page. */}
          <div className="flex items-center gap-2 px-3.25">
            <Checkbox
              focus="soft"
              id="library-select-page"
              // The scope is in the name, because a selection never leaves its page.
              aria-label={ui("Chọn tất cả trên trang này")}
              checked={
                selected.length === 0
                  ? false
                  : selected.length === files.length
                    ? true
                    : "indeterminate"
              }
              onCheckedChange={(checked) =>
                setSelected(checked === true ? files.map((file) => file.id) : [])
              }
            />
            <label
              htmlFor="library-select-page"
              className="cursor-pointer font-secondary-body text-content-muted"
            >
              {ui("Chọn tất cả")}
            </label>
          </div>
          <LibraryList
            files={files}
            view={view}
            layout={layout}
            selected={selected}
            grouped={sort === "NEWEST" && view !== "trash"}
            actions={actions}
            onSelect={(file) =>
              setSelected(
                selected.includes(file.id)
                  ? selected.filter((id) => id !== file.id)
                  : [...selected, file.id],
              )
            }
            onSelectDay={(day, pick) => {
              const ids = day.map((file) => file.id);
              setSelected(
                pick
                  ? [...selected, ...ids.filter((id) => !selected.includes(id))]
                  : selected.filter((id) => !ids.includes(id)),
              );
            }}
          />
        </div>
      )}
      {page.data && (
        <LibraryPager
          library={library}
          noun="file"
          total={page.data.totalCount}
          hasMore={page.data.hasMore}
          busy={page.isPlaceholderData}
        />
      )}
    </>
  );
}

/**
 * The pager under a list, which also carries the page size. A list no longer than the default page has nothing to
 * page or to size, so it shows none unless the person is past its first page; a longer one offers pages of each
 * size or the whole list. Files are counted as files, the other views' rows as items.
 */
export function LibraryPager({
  library,
  noun,
  total,
  hasMore,
  busy,
}: {
  library: LibraryViewState;
  noun: "file" | "item";
  total: number;
  /** Whether rows follow the page shown. */
  hasMore: boolean;
  /** While the next page is on its way, the size cannot change under it. */
  busy: boolean;
}) {
  const ui = useAppTranslation();
  const { offset, size } = library;
  if (total <= LIBRARY_PAGE_SIZE && offset === 0) return null;
  // The whole list is one page, as long as the list itself.
  const step = size === "all" ? Math.max(total, 1) : size;
  const range = { first: Math.min(offset + 1, total), last: Math.min(offset + step, total), total };
  return (
    <TablePagination
      label={ui("Phân trang thư viện")}
      page={Math.floor(offset / step)}
      totalPages={Math.ceil(total / step)}
      summary={
        noun === "file"
          ? ui("Hiển thị {{first}}–{{last}} trên {{total}} tệp", range)
          : ui("Hiển thị {{first}}–{{last}} trên {{total}} mục", range)
      }
      previousDisabled={offset === 0}
      nextDisabled={!hasMore}
      previousLabel={ui("Trang trước")}
      nextLabel={ui("Trang sau")}
      onPrevious={() => library.showPage(Math.max(0, offset - step))}
      onNext={() => library.showPage(offset + step)}
    >
      <LibraryPageSizeSelect library={library} noun={noun} disabled={busy} />
    </TablePagination>
  );
}

/** The page sizes of the library's lists, and the whole list. A new size re-cuts the list from its first page. */
export function LibraryPageSizeSelect({
  library,
  noun,
  disabled,
}: {
  library: LibraryViewState;
  noun: "file" | "item";
  disabled: boolean;
}) {
  const ui = useAppTranslation();
  return (
    <PageSizeSelect<LibraryPageSize>
      label={noun === "file" ? ui("Số tệp mỗi trang") : ui("Số mục mỗi trang")}
      rowsLabel={noun === "file" ? ui("Số tệp") : ui("Số mục")}
      value={library.size}
      sizes={[...LIBRARY_PAGE_SIZES, "all"]}
      sizeLabel={(size) => (size === "all" ? ui("Tất cả") : String(size))}
      disabled={disabled}
      onSizeChange={(next) => library.filterBy({ size: next })}
    />
  );
}

/** What a phrase inside a file found, which is a different list from the library's own. */
export function ContentResults({
  query,
  matches,
  onOpen,
  actions,
}: {
  query: string;
  matches: UseQueryResult<ContentMatch[], unknown>;
  onOpen: (file: LibraryFile) => void;
  actions: RowActions;
}) {
  const ui = useAppTranslation();
  return (
    <div className="flex flex-col gap-3">
      {query.length === 0 && (
        <p role="status" className="text-content-muted">
          {ui("Nhập điều bạn nhớ về nội dung tệp.")}
        </p>
      )}
      {matches.isFetching && <LibraryListSkeleton />}
      {matches.isError && (
        <Alert variant="destructive">
          <AlertTitle>{ui("Không tìm được trong nội dung tệp.")}</AlertTitle>
          <AlertDescription>
            <Button prominence="internal" size="sm" onClick={() => void matches.refetch()}>
              {ui("Thử lại")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {matches.isSuccess &&
        !matches.isFetching &&
        matches.data.length === 0 &&
        query.length > 0 && (
          <p role="status" className="text-content-muted">
            {ui("Không có tệp nào khớp nội dung này.")}
          </p>
        )}
      {matches.data && matches.data.length > 0 && (
        <LibraryContentMatches
          matches={matches.data}
          query={query}
          onOpen={(match) => onOpen(match.file)}
          actions={(match) => <FileActions file={match.file} actions={actions} />}
        />
      )}
    </div>
  );
}

/** The shape of a list while its first page is on its way. */
export function LibraryListSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-hidden="true">
      {[0, 1, 2, 3, 4].map((row) => (
        <Skeleton key={row} className="h-14 w-full" />
      ))}
    </div>
  );
}
