import {
  ArrowDownUp,
  Download,
  FolderPlus,
  LayoutGrid,
  List,
  ListFilter,
  Search,
  Trash2,
  X,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/radix-select";
import { Separator } from "@/components/ui/separator";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { cn } from "@/lib/utils";
import {
  LIBRARY_CATEGORIES,
  type LibraryCategory,
  type LibrarySort,
  type LibrarySource,
} from "./chat-library";
import { categoryLabels, sourceLabels } from "./chat-library-labels";

export type LibraryLayout = "list" | "grid";
export type LibrarySearchMode = "name" | "content";

const SOURCES: LibrarySource[] = ["UPLOAD", "GENERATED", "IMAGE"];
/** The orders worth offering; the trash and the processing view order themselves and hide the control. */
const SORTS: LibrarySort[] = ["NEWEST", "OLDEST", "NAME", "LARGEST", "SMALLEST"];
const CATEGORIES: readonly LibraryCategory[] = LIBRARY_CATEGORIES;

export type LibraryToolbarState = {
  search: string;
  mode: LibrarySearchMode;
  sources: LibrarySource[];
  categories: LibraryCategory[];
  sort: LibrarySort;
  layout: LibraryLayout;
};

export type LibraryToolbarHandlers = {
  onSearch: (next: string) => void;
  onMode: (next: LibrarySearchMode) => void;
  onSources: (next: LibrarySource[]) => void;
  onCategories: (next: LibraryCategory[]) => void;
  onSort: (next: LibrarySort) => void;
  onLayout: (next: LibraryLayout) => void;
};

/**
 * One row of controls: what to search, how to search it, what to keep and how to show it. The source and
 * category filters live in a popover instead of a wall of chips, and what is active comes back as removable
 * pills below, so the row stays the same height however many filters are on. Favourites are a view on the
 * rail rather than a filter here, so one thing is not asked for in two places.
 */
export function LibraryToolbar({
  state,
  handlers,
  sortable,
}: {
  state: LibraryToolbarState;
  handlers: LibraryToolbarHandlers;
  /** The trash and the processing view order themselves, so they hide the sort control. */
  sortable: boolean;
}) {
  const ui = useAppTranslation();
  const searching = state.mode === "content";
  const activeFilters = state.sources.length + state.categories.length;
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="relative min-w-56 flex-1">
        <Search
          className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-content-muted"
          aria-hidden="true"
        />
        <Input
          value={state.search}
          onChange={(event) => handlers.onSearch(event.target.value)}
          placeholder={searching ? ui("Tìm trong nội dung tệp") : ui("Tìm theo tên tệp")}
          aria-label={searching ? ui("Tìm trong nội dung tệp") : ui("Tìm theo tên tệp")}
          maxLength={200}
          className={cn("pl-9", state.search.length > 0 && "pr-9")}
        />
        {state.search.length > 0 && (
          <button
            type="button"
            aria-label={ui("Xoá từ khoá tìm kiếm")}
            onClick={() => handlers.onSearch("")}
            className="absolute top-1/2 right-2 grid size-6 -translate-y-1/2 place-items-center rounded-md text-content-muted outline-none hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
          >
            <X className="size-4" aria-hidden="true" />
          </button>
        )}
      </div>
      <ToggleGroup
        type="single"
        size="sm"
        value={state.mode}
        aria-label={ui("Cách tìm")}
        onValueChange={(value) => value && handlers.onMode(value as LibrarySearchMode)}
      >
        <ToggleGroupItem value="name" size="sm">
          {ui("Tên")}
        </ToggleGroupItem>
        <ToggleGroupItem value="content" size="sm">
          {ui("Nội dung")}
        </ToggleGroupItem>
      </ToggleGroup>
      <Popover>
        <PopoverTrigger asChild>
          <Button size="sm" prominence="secondary">
            <ListFilter className="size-4" aria-hidden="true" />
            {ui("Bộ lọc")}
            {activeFilters > 0 && (
              <Badge variant="secondary" className="tabular-nums">
                {activeFilters}
              </Badge>
            )}
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="w-72">
          <LibraryFilterPanel state={state} handlers={handlers} />
        </PopoverContent>
      </Popover>
      {sortable && <LibrarySortSelect sort={state.sort} onSort={handlers.onSort} />}
      <ToggleGroup
        type="single"
        size="sm"
        value={state.layout}
        aria-label={ui("Cách hiển thị")}
        onValueChange={(value) => value && handlers.onLayout(value as LibraryLayout)}
      >
        <ToggleGroupItem value="list" size="sm" aria-label={ui("Dạng danh sách")}>
          <List className="size-4" aria-hidden="true" />
        </ToggleGroupItem>
        <ToggleGroupItem value="grid" size="sm" aria-label={ui("Dạng lưới")}>
          <LayoutGrid className="size-4" aria-hidden="true" />
        </ToggleGroupItem>
      </ToggleGroup>
    </div>
  );
}

/**
 * How the list is ordered. It is the registry's own select rather than a native one: the browser draws a
 * native option list in the system's colours, which on this page reads as a foreign control beside the
 * filter, the search mode and the layout switch.
 */
function LibrarySortSelect({
  sort,
  onSort,
}: {
  sort: LibrarySort;
  onSort: (next: LibrarySort) => void;
}) {
  const ui = useAppTranslation();
  const labels: Record<LibrarySort, string> = {
    NEWEST: ui("Mới nhất"),
    OLDEST: ui("Cũ nhất"),
    NAME: ui("Tên A → Z"),
    LARGEST: ui("Dung lượng giảm dần"),
    SMALLEST: ui("Dung lượng tăng dần"),
    DELETED: ui("Xoá gần nhất"),
  };
  return (
    <Select value={sort} onValueChange={(next) => onSort(next as LibrarySort)}>
      <SelectTrigger aria-label={ui("Sắp xếp")} className="w-44">
        <ArrowDownUp className="size-4 text-content-muted" aria-hidden="true" />
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {SORTS.map((value) => (
          <SelectItem key={value} value={value}>
            {labels[value]}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** The filters themselves: where a file came from and what kind of file it is. */
function LibraryFilterPanel({
  state,
  handlers,
}: {
  state: LibraryToolbarState;
  handlers: LibraryToolbarHandlers;
}) {
  const ui = useAppTranslation();
  const sources = sourceLabels(ui);
  const categories = categoryLabels(ui);
  const toggle = <T extends string>(values: T[], value: T) =>
    values.includes(value) ? values.filter((item) => item !== value) : [...values, value];
  const clearable = state.sources.length > 0 || state.categories.length > 0;
  return (
    <div className="flex flex-col gap-3">
      <fieldset className="flex flex-col gap-2">
        <legend className="font-secondary-body text-content-muted">{ui("Nguồn tệp")}</legend>
        <div className="flex flex-wrap gap-1.5">
          {SOURCES.map((source) => (
            <FilterChip
              key={source}
              label={sources[source]}
              pressed={state.sources.includes(source)}
              onToggle={() => handlers.onSources(toggle(state.sources, source))}
            />
          ))}
        </div>
      </fieldset>
      <Separator />
      <fieldset className="flex flex-col gap-2">
        <legend className="font-secondary-body text-content-muted">{ui("Loại tệp")}</legend>
        <div className="flex flex-wrap gap-1.5">
          {CATEGORIES.map((category) => (
            <FilterChip
              key={category}
              label={categories[category]}
              pressed={state.categories.includes(category)}
              onToggle={() => handlers.onCategories(toggle(state.categories, category))}
            />
          ))}
        </div>
      </fieldset>
      {clearable && (
        <Button
          size="sm"
          prominence="internal"
          className="self-start"
          onClick={() => {
            handlers.onSources([]);
            handlers.onCategories([]);
          }}
        >
          {ui("Xoá bộ lọc")}
        </Button>
      )}
    </div>
  );
}

/** What the current filter keeps, as pills the owner can take off one at a time. */
export function LibraryFilterPills({
  state,
  handlers,
}: {
  state: LibraryToolbarState;
  handlers: LibraryToolbarHandlers;
}) {
  const ui = useAppTranslation();
  const sources = sourceLabels(ui);
  const categories = categoryLabels(ui);
  const pills = [
    ...state.sources.map((source) => ({
      key: `source:${source}`,
      label: sources[source],
      remove: () => handlers.onSources(state.sources.filter((item) => item !== source)),
    })),
    ...state.categories.map((category) => ({
      key: `category:${category}`,
      label: categories[category],
      remove: () => handlers.onCategories(state.categories.filter((item) => item !== category)),
    })),
  ];
  if (pills.length === 0) return null;
  return (
    <ul aria-label={ui("Bộ lọc đang áp dụng")} className="flex flex-wrap items-center gap-1.5">
      {pills.map((pill) => (
        <li key={pill.key}>
          <button
            type="button"
            onClick={pill.remove}
            aria-label={ui("Bỏ lọc {{name}}", { name: pill.label })}
            className="flex h-7 items-center gap-1 rounded-full border border-border-subtle bg-surface-subtle px-2.5 font-secondary-body text-content-secondary outline-none hover:text-content-primary focus-visible:ring-3 focus-visible:ring-focus-ring/40"
          >
            {pill.label}
            <X className="size-3.5" aria-hidden="true" />
          </button>
        </li>
      ))}
      <li>
        <Button
          size="sm"
          prominence="internal"
          onClick={() => {
            handlers.onSources([]);
            handlers.onCategories([]);
          }}
        >
          {ui("Xoá tất cả")}
        </Button>
      </li>
    </ul>
  );
}

function FilterChip({
  label,
  pressed,
  onToggle,
}: {
  label: string;
  pressed: boolean;
  onToggle: () => void;
}) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onToggle}
      className={cn(
        "h-7 rounded-full border px-3 font-secondary-body transition-colors outline-none focus-visible:ring-3 focus-visible:ring-focus-ring/40",
        pressed
          ? "border-transparent bg-surface-accent text-content-on-accent"
          : "border-border-default text-content-secondary hover:text-content-primary",
      )}
    >
      {label}
    </button>
  );
}

/**
 * What a selection can do, over the list rather than above the page, so the commands stay in reach while the
 * owner scrolls. It names how many files it acts on, because a refused delete reports per file.
 */
export function LibrarySelectionBar({
  count,
  packing,
  onDownload,
  onAddToProject,
  onDelete,
  onClear,
}: {
  count: number;
  packing: boolean;
  onDownload: () => void;
  onAddToProject: () => void;
  onDelete: () => void;
  onClear: () => void;
}) {
  const ui = useAppTranslation();
  return (
    <div className="sticky top-2 z-20 flex flex-wrap items-center gap-2 rounded-xl border border-border-default bg-surface-raised px-3 py-2 shadow-sm">
      <span className="font-main-ui-action tabular-nums">
        {ui("Đã chọn {{count}} tệp", { count })}
      </span>
      <span className="mx-1 hidden h-5 w-px bg-border-subtle sm:block" aria-hidden="true" />
      <Button size="sm" prominence="secondary" pending={packing} onClick={onDownload}>
        <Download className="size-4" aria-hidden="true" />
        {ui("Tải về ZIP")}
      </Button>
      <Button size="sm" prominence="secondary" onClick={onAddToProject}>
        <FolderPlus className="size-4" aria-hidden="true" />
        {ui("Thêm vào dự án")}
      </Button>
      <Button size="sm" tone="danger" prominence="secondary" onClick={onDelete}>
        <Trash2 className="size-4" aria-hidden="true" />
        {ui("Xoá")}
      </Button>
      <IconButton
        size="sm"
        prominence="internal"
        className="ml-auto"
        aria-label={ui("Bỏ chọn")}
        onClick={onClear}
      >
        <X />
      </IconButton>
    </div>
  );
}

/**
 * The file-kind filter on its own, for the surfaces that only narrow by kind: the conversation panel and the
 * composer's library picker. One button with a count reads the same on a sheet as on the page.
 */
export function LibraryCategoryFilter({
  categories,
  onCategories,
  size = "sm",
}: {
  categories: LibraryCategory[];
  onCategories: (next: LibraryCategory[]) => void;
  size?: "sm" | "md";
}) {
  const ui = useAppTranslation();
  const labels = categoryLabels(ui);
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button size={size} prominence="secondary">
          <ListFilter className="size-4" aria-hidden="true" />
          {ui("Loại tệp")}
          {categories.length > 0 && (
            <Badge variant="secondary" className="tabular-nums">
              {categories.length}
            </Badge>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64">
        <fieldset className="flex flex-col gap-2">
          <legend className="font-secondary-body text-content-muted">{ui("Loại tệp")}</legend>
          <div className="flex flex-wrap gap-1.5">
            {CATEGORIES.map((category) => (
              <FilterChip
                key={category}
                label={labels[category]}
                pressed={categories.includes(category)}
                onToggle={() =>
                  onCategories(
                    categories.includes(category)
                      ? categories.filter((item) => item !== category)
                      : [...categories, category],
                  )
                }
              />
            ))}
          </div>
          {categories.length > 0 && (
            <Button
              size="sm"
              prominence="internal"
              className="self-start"
              onClick={() => onCategories([])}
            >
              {ui("Xoá bộ lọc")}
            </Button>
          )}
        </fieldset>
      </PopoverContent>
    </Popover>
  );
}
