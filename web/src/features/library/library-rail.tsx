import { Building2, Clock, FolderOpen, LoaderCircle, Mic, Star, Trash2, Users } from "lucide-react";
import { Progress } from "@/components/ui/progress";
import { SidebarTab } from "@/components/ui/sidebar-tab";
import { TextButton } from "@/components/ui/text-button";
import { useGlobalCapability } from "@/features/identity/application-session-context";
import { useAppTranslation } from "@/i18n/use-app-translation";
import { i18n } from "@/i18n/index";
import { cn } from "@/lib/utils";
import { fileSize } from "@/lib/file-size";

/**
 * The views that read everything the person can see or use, each behind the capability that owns what it lists.
 * Their rows are references the server re-authorizes on every read.
 */
export type LibraryEntryView = "recent" | "shared" | "meetings" | "documents" | "starred";
/** The views of what the person owns: usable files, what is arriving, and the trash. */
export type LibraryOwnedView = "ready" | "pending" | "trash";
/** Which slice of the library is on screen. */
export type LibraryView = LibraryEntryView | LibraryOwnedView;

export type LibraryUsage = {
  usedBytes: number;
  fileCount: number;
  limitBytes?: number | null;
};

/** Where the storage bar turns into a warning: the owner still has room, but not much. */
const NEARLY_FULL = 90;

/**
 * The library's own navigation, beside the list rather than stacked above it: the views of what the person can
 * see or use, and what the account has stored. Above the page's rail breakpoint the views read as a column that
 * stays in place while the list scrolls, and scroll on their own when the window is too short to hold them; below
 * it they scroll as one row, so a phone keeps the list in view. The organisation's documents need Search, so a
 * person without it never sees that view.
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
  const canReadDocuments = useGlobalCapability("SEARCH_READ");
  const views: { value: LibraryView; label: string; icon: React.ReactNode }[] = [
    { value: "recent", label: ui("Gần đây"), icon: <Clock /> },
    { value: "ready", label: ui("Tệp của tôi"), icon: <FolderOpen /> },
    { value: "shared", label: ui("Được chia sẻ với tôi"), icon: <Users /> },
    { value: "meetings", label: ui("Cuộc họp"), icon: <Mic /> },
    ...(canReadDocuments
      ? [{ value: "documents" as const, label: ui("Tài liệu tổ chức"), icon: <Building2 /> }]
      : []),
    { value: "starred", label: ui("Có gắn sao"), icon: <Star /> },
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
              {(counts[entry.value] ?? 0) > 0 && (
                <span className="font-secondary-body text-content-muted tabular-nums">
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
      <p className="mt-1 font-main-ui-action text-content-primary tabular-nums">
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
