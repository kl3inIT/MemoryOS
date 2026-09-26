import type { ReactNode } from "react";
import type { UseQueryResult } from "@tanstack/react-query";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldLabel } from "@/components/ui/field";
import { PageSizeSelect } from "@/components/ui/page-size-select";
import { Skeleton } from "@/components/ui/skeleton";
import { TablePagination } from "@/components/ui/table-pagination";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import { LIBRARY_PAGE_SIZES, type ContentMatch, type LibraryFile } from "./library";
import { LibraryContentMatches } from "./library-content";
import { FileActions, LibraryEmpty, LibraryList, type RowActions } from "./library-rows";
import type { LibraryLayout } from "./library-toolbar";
import type { LibraryViewState } from "./use-library-view";

/**
 * The files of the page being read: a header whose checkbox chooses the whole page, the list or grid, and the
 * pagination bar, which also carries the page size.
 */
export function LibraryFiles({
  library,
  layout,
  actions,
  emptyAction,
}: {
  library: LibraryViewState;
  layout: LibraryLayout;
  actions: RowActions;
  /** The upload control an empty library offers, which owns the browser's file dialog. */
  emptyAction: ReactNode;
}) {
  const ui = useAppTranslation();
  const { page, files, selected, setSelected, offset, size, view, sort } = library;
  return (
    <>
      {page.isPending && <ListSkeleton />}
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
          {/* A list header, aligned to the rows' own checkbox column so the three checkbox gutters (page, day,
              file) read as one line down the page. */}
          <div className="border-b border-border-subtle px-3.25 pb-2">
            <Field orientation="horizontal">
              <Checkbox
                id="library-select-page"
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
              {/* The scope is on the label, because a selection never leaves its page. */}
              <FieldLabel htmlFor="library-select-page">
                {ui("Chọn tất cả trên trang này")}
              </FieldLabel>
            </Field>
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
      {/* The bar stays while there are files, because it also carries the page size. */}
      {page.data && page.data.totalCount > 0 && (
        <TablePagination
          label={ui("Phân trang thư viện")}
          page={Math.floor(offset / size)}
          totalPages={Math.ceil(page.data.totalCount / size)}
          summary={ui("Hiển thị {{first}}–{{last}} trên {{total}} tệp", {
            first: offset + 1,
            last: Math.min(offset + size, page.data.totalCount),
            total: page.data.totalCount,
          })}
          previousDisabled={offset === 0}
          nextDisabled={!page.data.hasMore}
          previousLabel={ui("Trang trước")}
          nextLabel={ui("Trang sau")}
          onPrevious={() => library.showPage(Math.max(0, offset - size))}
          onNext={() => library.showPage(offset + size)}
        >
          <PageSizeSelect
            label={ui("Số tệp mỗi trang")}
            rowsLabel={ui("Số tệp")}
            value={size}
            sizes={LIBRARY_PAGE_SIZES}
            disabled={page.isPlaceholderData}
            // A page size change re-cuts the list, so it restarts at its first page.
            onSizeChange={(next) => library.filterBy({ size: next })}
          />
        </TablePagination>
      )}
    </>
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
      {matches.isFetching && <ListSkeleton />}
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

function ListSkeleton() {
  return (
    <div className="flex flex-col gap-2" aria-hidden="true">
      {[0, 1, 2, 3, 4].map((row) => (
        <Skeleton key={row} className="h-14 w-full" />
      ))}
    </div>
  );
}
