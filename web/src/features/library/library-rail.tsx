import { FolderOpen, LoaderCircle, Star, Trash2 } from "lucide-react";
import { Progress } from "@/components/ui/progress";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { TextButton } from "@/components/ui/text-button";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { cn } from "@/lib/utils";
import { fileSize } from "@/lib/file-size";

/** Which slice of the library is on screen. Favourites are a slice of the usable files, not a state of their own. */
export type LibraryView = "ready" | "favorite" | "pending" | "trash";

export type LibraryUsage = {
  usedBytes: number;
  fileCount: number;
  limitBytes?: number | null;
};

/** Where the storage bar turns into a warning: the owner still has room, but not much. */
const NEARLY_FULL = 90;

/**
 * The library's own navigation, beside the list rather than stacked above it: the four slices a file can be in,
 * and what the account has stored. Above the page's rail breakpoint the views read as a column that stays in
 * place while the list scrolls, and scroll on their own when the window is too short to hold them; below it they
 * scroll as one row, so a phone keeps the list in view.
 */
export function LibraryRail({
  view,
  counts,
  usage,
  onView,
  onShowLargest,
}: {
  view: LibraryView;
  counts: Partial<Record<LibraryView, number>>;
  usage?: LibraryUsage;
  onView: (next: LibraryView) => void;
  onShowLargest: () => void;
}) {
  const ui = useAppTranslation();
  const views: { value: LibraryView; label: string; icon: React.ReactNode }[] = [
    { value: "ready", label: ui("Tất cả tệp"), icon: <FolderOpen /> },
    { value: "favorite", label: ui("Yêu thích"), icon: <Star /> },
    { value: "pending", label: ui("Đang xử lý"), icon: <LoaderCircle /> },
    { value: "trash", label: ui("Thùng rác"), icon: <Trash2 /> },
  ];
  return (
    <div className="flex flex-col gap-6 lg:sticky lg:top-6 lg:max-h-[calc(100dvh-6rem)] lg:self-start lg:overflow-y-auto lg:overscroll-contain">
      <nav
        aria-label={ui("Phần của thư viện")}
        className="-mx-1 flex gap-1 overflow-x-auto px-1 pb-1 lg:mx-0 lg:flex-col lg:overflow-visible lg:px-0 lg:pb-0"
      >
        {views.map((entry) => (
          <SidebarTab
            key={entry.value}
            icon={entry.icon}
            selected={view === entry.value}
            variant="light"
            className="w-auto shrink-0 lg:w-full"
            onClick={() => onView(entry.value)}
          >
            <span className="flex items-center gap-2">
              <span className="truncate">{entry.label}</span>
              {counts[entry.value] !== undefined && counts[entry.value]! > 0 && (
                <span className="font-secondary-body tabular-nums text-content-muted">
                  {counts[entry.value]}
                </span>
              )}
            </span>
          </SidebarTab>
        ))}
      </nav>
      {usage && <StorageCard usage={usage} onShowLargest={onShowLargest} />}
    </div>
  );
}

/** What the account has stored against what it may store, with the one action that helps when it runs out. */
function StorageCard({ usage, onShowLargest }: { usage: LibraryUsage; onShowLargest: () => void }) {
  const ui = useAppTranslation();
  const limit = usage.limitBytes ?? null;
  const percent = limit ? Math.min(100, Math.round((usage.usedBytes / limit) * 100)) : 0;
  const nearlyFull = limit !== null && percent >= NEARLY_FULL;
  return (
    <section
      aria-label={ui("Dung lượng đã dùng")}
      className="rounded-xl border border-border-subtle p-3"
    >
      <h2 className="font-secondary-body text-content-muted">{ui("Dung lượng")}</h2>
      <p className="mt-1 font-main-ui-action tabular-nums text-content-primary">
        {fileSize(usage.usedBytes, i18n.language)}
        {limit !== null && (
          <span className="text-content-muted">
            {" / "}
            {fileSize(limit, i18n.language)}
          </span>
        )}
      </p>
      {limit === null ? (
        <p className="mt-1 font-secondary-body text-content-muted">{ui("Không giới hạn")}</p>
      ) : (
        <>
          <Progress
            value={percent}
            aria-label={ui("Dung lượng đã dùng")}
            tone={nearlyFull ? "danger" : "default"}
            className="mt-2"
          />
          <p
            className={cn(
              "mt-1.5 font-secondary-body",
              nearlyFull ? "text-status-danger-content" : "text-content-muted",
            )}
          >
            {nearlyFull
              ? ui("Gần hết dung lượng · {{percent}}%", { percent })
              : ui("Đã dùng {{percent}}%", { percent })}
          </p>
        </>
      )}
      <p className="mt-2 font-secondary-body text-content-muted">
        {ui("{{count}} tệp", { count: usage.fileCount })}
      </p>
      <TextButton size="sm" className="mt-1" onClick={onShowLargest}>
        {ui("Xem tệp lớn nhất")}
      </TextButton>
    </section>
  );
}
