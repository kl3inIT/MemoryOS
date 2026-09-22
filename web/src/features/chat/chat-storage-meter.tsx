import { Link } from "@tanstack/react-router";
import { ChevronRight, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Item,
  ItemActions,
  ItemContent,
  ItemDescription,
  ItemMedia,
  ItemTitle,
} from "@/components/ui/item";
import { Progress } from "@/components/ui/progress";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n";
import { cn } from "@/lib/utils";
import { fileSize } from "./chat-code";
import { type loadLibraryUsage, type LibraryCategory } from "./chat-library";
import { categoryLabels } from "./chat-library-labels";
import { CATEGORY_ICONS } from "./chat-library-icons";

/** Past this share of the limit the meter says so, because the next upload is the one that fails. */
const NEARLY_FULL = 90;

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
  // The library's own order: the biggest kind first, because that is the one worth clearing.
  const categories = [...usage.byCategory]
    .filter((entry) => entry.usedBytes > 0)
    .sort((left, right) => right.usedBytes - left.usedBytes);

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      {/*
       * The same surface every settings card uses, so the meter and the retention rows below it read as one
       * page rather than two tones on the same background.
       */}
      <section
        aria-label={ui("Dung lượng đã dùng")}
        className="flex flex-col gap-2 rounded-xl border border-border-subtle bg-surface-raised p-4"
      >
        <p className="font-main-ui-action tabular-nums text-content-primary">
          {limit === null
            ? ui("Đã dùng {{used}}", { used: fileSize(usage.usedBytes, i18n.language) })
            : ui("Đã dùng {{used}} / {{limit}}", {
                used: fileSize(usage.usedBytes, i18n.language),
                limit: fileSize(limit, i18n.language),
              })}
        </p>
        {limit === null ? (
          <p className="font-secondary-body text-content-muted">
            {ui("Triển khai này không đặt giới hạn dung lượng.")}
          </p>
        ) : (
          <>
            <Progress
              value={percent}
              aria-label={ui("Dung lượng đã dùng")}
              className={cn(
                nearlyFull &&
                  "[&_[data-slot=progress-indicator]]:bg-status-danger-emphasis-surface",
              )}
            />
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
          </>
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
          <ul className="flex flex-col gap-2">
            {categories.map((entry) => {
              const category = entry.category as LibraryCategory;
              const Icon = CATEGORY_ICONS[category];
              const row = (
                <>
                  <ItemMedia>
                    <Icon className="size-4 text-content-muted" aria-hidden="true" />
                  </ItemMedia>
                  <ItemContent>
                    <ItemTitle>{labels[category]}</ItemTitle>
                    <ItemDescription>{fileSize(entry.usedBytes, i18n.language)}</ItemDescription>
                  </ItemContent>
                  <ItemActions>
                    <ChevronRight className="size-4 text-content-muted" aria-hidden="true" />
                  </ItemActions>
                </>
              );
              return (
                <li key={entry.category}>
                  <Item asChild variant="outline" className="bg-surface-raised hover:bg-surface-subtle">
                    {onCategory ? (
                      <button type="button" onClick={() => onCategory(category)}>
                        {row}
                      </button>
                    ) : (
                      <Link to="/library" search={{ category }}>
                        {row}
                      </Link>
                    )}
                  </Item>
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
        {/*
         * The trash counts against the limit until it is emptied, so the line names what it holds rather than
         * offering a link: `/library` takes a category, and its trash view is page state with no search param
         * to address, so there is nothing to link to without inventing a route.
         */}
        <p className="font-secondary-body text-content-muted">
          <Trash2 className="mr-1 inline size-3.5" aria-hidden="true" />
          {usage.trashedBytes > 0
            ? ui("Thùng rác đang giữ {{size}} — dọn để giải phóng ngay.", {
                size: fileSize(usage.trashedBytes, i18n.language),
              })
            : ui("Tệp đã xoá vẫn chiếm dung lượng cho tới khi thùng rác được dọn.")}
        </p>
      </section>
    </div>
  );
}
