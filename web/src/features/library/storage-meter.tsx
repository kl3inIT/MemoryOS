import { Link } from "@tanstack/react-router";
import { ChevronRight, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { cn } from "@/lib/utils";
import { fileSize } from "@/lib/file-size";
import { type loadLibraryUsage, type LibraryCategory } from "./library";
import { categoryLabels } from "./library-labels";
import { CATEGORY_ICONS } from "./library-icons";

/** Past this share of the limit the meter says so, because the next upload is the one that fails. */
const NEARLY_FULL = 90;

/**
 * One fixed hue per kind of file, as a phone's storage bar does it: the colour means the same thing in the
 * bar and in the row below it, whatever order the kinds happen to be in. "Khác" keeps the neutral series, so
 * a named kind is never mistaken for the leftovers.
 */
const CATEGORY_COLOURS: Record<LibraryCategory, string> = {
  DOCUMENT: "var(--chart-1)",
  SPREADSHEET: "var(--chart-6)",
  IMAGE: "var(--chart-3)",
  PRESENTATION: "var(--chart-2)",
  OTHER: "var(--chart-neutral)",
};

export type LibraryUsage = Awaited<ReturnType<typeof loadLibraryUsage>>;

/**
 * What this person has stored and what they may store (ChatGPT "Bộ nhớ lưu trữ"): the meter, then one row per
 * kind of file that leads to those files in the library, where things are actually deleted. The maximum is a
 * deployment setting rather than something anyone administers, so the meter states it and never offers to
 * change it.
 *
 * <p>The same meter serves the storage settings page and the library's own settings panel. On the settings
 * page a category row is a link into the library; on the library page it narrows the list already on screen,
 * which is what `onCategory` is for.
 */
export function StorageMeter({
  usage,
  onCategory,
}: {
  usage: LibraryUsage;
  /** Given on a surface that owns the library listing: the row narrows that list instead of navigating. */
  onCategory?: (category: LibraryCategory) => void;
}) {
  const ui = useAppTranslation();
  const labels = categoryLabels(ui);
  const limit = usage.limitBytes ?? null;
  const percent = limit ? Math.min(100, Math.round((usage.usedBytes / limit) * 100)) : 0;
  const nearlyFull = limit !== null && percent >= NEARLY_FULL;
  // The biggest kind first, because that is the one worth clearing; "Khác" is the leftovers and reads last
  // however big it is, in the bar and in the rows alike.
  const categories = [...usage.byCategory]
    .filter((entry) => entry.usedBytes > 0)
    .sort(
      (left, right) =>
        Number(left.category === "OTHER") - Number(right.category === "OTHER") ||
        right.usedBytes - left.usedBytes,
    );

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <section
        aria-label={ui("Dung lượng đã dùng")}
        className="flex flex-col gap-2 rounded-xl border border-border-subtle p-4"
      >
        <p className="font-main-ui-action tabular-nums text-content-primary">
          {limit === null
            ? ui("Đã dùng {{used}}", { used: fileSize(usage.usedBytes, i18n.language) })
            : ui("Đã dùng {{used}} / {{limit}}", {
                used: fileSize(usage.usedBytes, i18n.language),
                limit: fileSize(limit, i18n.language),
              })}
        </p>
        <StorageBar
          categories={categories}
          labels={labels}
          scale={limit ?? usage.usedBytes}
          full={limit !== null}
        />
        {limit === null ? (
          <p className="font-secondary-body text-content-muted">
            {ui("Triển khai này không đặt giới hạn dung lượng · {{count}} tệp", {
              count: usage.fileCount,
            })}
          </p>
        ) : (
          <p
            className={cn(
              "font-secondary-body",
              nearlyFull ? "text-status-danger-content" : "text-content-muted",
            )}
          >
            {nearlyFull
              ? ui("Gần hết dung lượng · {{percent}}% · hãy xoá bớt tệp", { percent })
              : ui("Đã dùng {{percent}}% · {{count}} tệp", { percent, count: usage.fileCount })}
          </p>
        )}
      </section>

      <section aria-label={ui("Quản lý bộ nhớ lưu trữ")} className="flex flex-col gap-2">
        <div>
          <h2 className="font-main-ui-action text-content-primary">
            {ui("Quản lý bộ nhớ lưu trữ")}
          </h2>
          <p className="font-secondary-body text-content-muted">
            {onCategory
              ? ui("Chọn một loại tệp để xem và xoá bớt.")
              : ui("Mở thư viện để giải phóng dung lượng.")}
          </p>
        </div>
        {categories.length === 0 ? (
          <p className="font-secondary-body text-content-muted">
            {ui("Thư viện của bạn chưa có tệp nào.")}
          </p>
        ) : (
          // One line per kind: name, what it costs, and the way into it. Five kinds then take the height of
          // two of the old rows, which is what leaves room for the settings below them.
          <ul className="flex flex-col">
            {categories.map((entry) => {
              const category = entry.category as LibraryCategory;
              const Icon = CATEGORY_ICONS[category];
              const row = (
                <>
                  {/* The kind's own colour, the same one it holds in the bar above. */}
                  <Icon
                    className="size-4 shrink-0"
                    style={{ color: CATEGORY_COLOURS[category] }}
                    aria-hidden="true"
                  />
                  <span className="min-w-0 flex-1 truncate">{labels[category]}</span>
                  <span className="shrink-0 tabular-nums text-content-muted">
                    {fileSize(entry.usedBytes, i18n.language)}
                  </span>
                  <ChevronRight className="size-4 shrink-0 text-content-muted" aria-hidden="true" />
                </>
              );
              const className =
                "flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left font-main-ui-body outline-none hover:bg-surface-subtle focus-visible:ring-3 focus-visible:ring-focus-ring/40";
              return (
                <li key={entry.category}>
                  {onCategory ? (
                    <button
                      type="button"
                      className={className}
                      onClick={() => onCategory(category)}
                    >
                      {row}
                    </button>
                  ) : (
                    <Link to="/library" search={{ category }} className={className}>
                      {row}
                    </Link>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {!onCategory && (
          <Button asChild prominence="secondary" className="mt-2 self-start">
            <Link to="/library">{ui("Mở thư viện tệp")}</Link>
          </Button>
        )}
        <p className="font-secondary-body text-content-muted">
          <Trash2 className="mr-1 inline size-3.5" aria-hidden="true" />
          {ui("Tệp đã xoá vẫn chiếm dung lượng cho tới khi thùng rác được dọn.")}
        </p>
      </section>
    </div>
  );
}

/**
 * The bar itself: one slice per kind of file in the fixed order of its colours, the way a phone shows what
 * fills its storage. With a limit the slices are read against it and the rest of the track is the room left;
 * without one they are read against each other, because there is nothing left to be short of.
 */
function StorageBar({
  categories,
  labels,
  scale,
  full,
}: {
  categories: readonly { category: string; usedBytes: number }[];
  labels: Record<LibraryCategory, string>;
  scale: number;
  full: boolean;
}) {
  const ui = useAppTranslation();
  const total = scale > 0 ? scale : 1;
  return (
    <div
      role="img"
      aria-label={categories
        .map((entry) =>
          ui("{{name}}: {{size}}", {
            name: labels[entry.category as LibraryCategory],
            size: fileSize(entry.usedBytes, i18n.language),
          }),
        )
        .join(", ")}
      className="flex h-2.5 w-full overflow-hidden rounded-full bg-surface-sunken"
    >
      {categories.map((entry) => (
        <span
          key={entry.category}
          title={ui("{{name}}: {{size}}", {
            name: labels[entry.category as LibraryCategory],
            size: fileSize(entry.usedBytes, i18n.language),
          })}
          className="h-full first:rounded-l-full last:rounded-r-full"
          style={{
            // A kind worth a pixel keeps one: a sliver that rounds to nothing still says the kind is there.
            width: `${Math.max((entry.usedBytes / total) * 100, full ? 0.75 : 1)}%`,
            backgroundColor: CATEGORY_COLOURS[entry.category as LibraryCategory],
          }}
        />
      ))}
    </div>
  );
}
